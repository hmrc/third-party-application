/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.thirdpartyapplication.services.commands

import java.time.{Clock, LocalDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationId
}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.Status._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeResponsibleIndividualToOther

@Singleton
class ChangeResponsibleIndividualToOtherCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    stateHistoryRepository: StateHistoryRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    submissionsService: SubmissionsService,
    clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def isNotCurrentRi(name: String, email: LaxEmailAddress, app: ApplicationData) =
    cond(
      app.access match {
        case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) =>
          !responsibleIndividual.fullName.value.equalsIgnoreCase(name) || !responsibleIndividual.emailAddress.equalsIgnoreCase(email)
        case _                                                                                            => true
      },
      s"The specified individual is already the RI for this application"
    )

  private def isApplicationIdTheSame(app: ApplicationData, riVerification: ResponsibleIndividualVerification) =
    cond(app.id == riVerification.applicationId, "The given application id is different")

  def processTou(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerificationToU: ResponsibleIndividualToUVerification): AppCmdResultT = {
    def validate(): Validated[Failures, (ResponsibleIndividual, LaxEmailAddress, String)] = {
      Apply[Validated[Failures, *]].map6(
        isStandardNewJourneyApp(app),
        isPendingResponsibleIndividualVerification(app),
        isApplicationIdTheSame(app, riVerificationToU),
        ensureResponsibleIndividualDefined(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app)
      ) { case (_, _, _, responsibleIndividual, requesterEmail, requesterName) => (responsibleIndividual, requesterEmail, requesterName) }
    }

    def asEvents(
        stateHistory: StateHistory,
        responsibleIndividual: ResponsibleIndividual,
        requesterEmail: LaxEmailAddress,
        requesterName: String
      ): (ResponsibleIndividualSet, ApplicationStateChanged) = {
      (
        ResponsibleIndividualSet(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp.instant,
          actor = Actors.AppCollaborator(requesterEmail),
          responsibleIndividualName = responsibleIndividual.fullName.value,
          responsibleIndividualEmail = responsibleIndividual.emailAddress,
          submissionId = SubmissionId(riVerificationToU.submissionId.value),
          submissionIndex = riVerificationToU.submissionInstance,
          code = cmd.code,
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        ),
        ApplicationStateChanged(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp.instant,
          actor = Actors.AppCollaborator(requesterEmail),
          app.state.name.toString,
          State.PENDING_GATEKEEPER_APPROVAL.toString,
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        )
      )
    }

    for {
      valid                                                 <- E.fromEither(validate().toEither)
      (responsibleIndividual, requesterEmail, requesterName) = valid
      _                                                     <- E.liftF(applicationRepository.updateApplicationSetResponsibleIndividual(
                                                                 app.id,
                                                                 responsibleIndividual.fullName.value,
                                                                 responsibleIndividual.emailAddress,
                                                                 cmd.timestamp,
                                                                 riVerificationToU.submissionId,
                                                                 riVerificationToU.submissionInstance
                                                               ))
      savedApp                                              <- E.liftF(applicationRepository.updateApplicationState(app.id, State.PENDING_GATEKEEPER_APPROVAL, cmd.timestamp, requesterEmail.text, requesterName))
      _                                                     <- E.liftF(responsibleIndividualVerificationRepository.deleteResponsibleIndividualVerification(cmd.code))
      stateHistory                                           = StateHistory(app.id, State.PENDING_GATEKEEPER_APPROVAL, Actors.AppCollaborator(requesterEmail), Some(app.state.name), changedAt = cmd.timestamp)
      _                                                     <- E.liftF(stateHistoryRepository.insert(stateHistory))
      (riEvt, stateEvt)                                      = asEvents(stateHistory, responsibleIndividual, requesterEmail, requesterName)
    } yield (savedApp, NonEmptyList(riEvt, List(stateEvt)))
  }

  def processTouUplift(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerificationToU: ResponsibleIndividualTouUpliftVerification): AppCmdResultT = {
    def validate(): Validated[Failures, (ResponsibleIndividual, LaxEmailAddress, String)] = {
      Apply[Validated[Failures, *]].map6(
        isStandardNewJourneyApp(app),
        isInProduction(app),
        isApplicationIdTheSame(app, riVerificationToU),
        ensureResponsibleIndividualDefined(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app)
      ) { case (_, _, _, responsibleIndividual, requesterEmail, requesterName) => (responsibleIndividual, requesterEmail, requesterName) }
    }

    def asEvents(
        isPassed: Boolean,
        responsibleIndividual: ResponsibleIndividual,
        requesterEmail: LaxEmailAddress,
        requesterName: String
      ): NonEmptyList[ApplicationEvent] = {
      if (isPassed) {
        NonEmptyList.of(
          getResponsibleIndividualSet(
            responsibleIndividual,
            requesterEmail,
            requesterName
          ),
          TermsOfUsePassed(
            id = EventId.random,
            applicationId = app.id,
            eventDateTime = cmd.timestamp.instant,
            actor = Actors.AppCollaborator(requesterEmail),
            submissionId = SubmissionId(riVerificationToU.submissionId.value),
            submissionIndex = riVerificationToU.submissionInstance
          )
        )
      } else {
        NonEmptyList.of(
          getResponsibleIndividualSet(
            responsibleIndividual,
            requesterEmail,
            requesterName
          )
        )
      }
    }

    def getResponsibleIndividualSet(
        responsibleIndividual: ResponsibleIndividual,
        requesterEmail: LaxEmailAddress,
        requesterName: String
      ): ResponsibleIndividualSet = {
      ResponsibleIndividualSet(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = Actors.AppCollaborator(requesterEmail),
        responsibleIndividualName = responsibleIndividual.fullName.value,
        responsibleIndividualEmail = responsibleIndividual.emailAddress,
        submissionId = SubmissionId(riVerificationToU.submissionId.value),
        submissionIndex = riVerificationToU.submissionInstance,
        code = cmd.code,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      )
    }

    def addTouAcceptanceIfNeeded(
        addTouAcceptance: Boolean,
        appWithoutTouAcceptance: ApplicationData,
        submissionId: SubmissionId,
        submissionInstance: Int,
        responsibleIndividual: ResponsibleIndividual
      ): Future[ApplicationData] = {
      if (addTouAcceptance) {
        val acceptance = TermsOfUseAcceptance(responsibleIndividual, LocalDateTime.now(clock), submissionId, submissionInstance)
        applicationRepository.addApplicationTermsOfUseAcceptance(appWithoutTouAcceptance.id, acceptance)
      } else {
        Future.successful(appWithoutTouAcceptance)
      }
    }

    def setTermsOfUseInvitationStatus(applicationId: ApplicationId, submission: Submission) = {
      submission.status match {
        case Granted(_, _)  => termsOfUseInvitationRepository.updateState(applicationId, TERMS_OF_USE_V2)
        case Warnings(_, _) => termsOfUseInvitationRepository.updateState(applicationId, WARNINGS)
        case Failed(_, _)   => termsOfUseInvitationRepository.updateState(applicationId, FAILED)
        case _              => Future.successful(HasSucceeded)
      }
    }

    for {
      valid                                                 <- E.fromValidated(validate())
      (responsibleIndividual, requesterEmail, requesterName) = valid
      submission                                            <- E.fromOptionF(submissionsService.markSubmission(app.id, requesterEmail.text), NonEmptyList.one(CommandFailures.GenericFailure("Submission not found")))
      _                                                     <- E.liftF(setTermsOfUseInvitationStatus(app.id, submission))
      isPassed                                               = submission.status.isGranted
      _                                                     <- E.liftF(addTouAcceptanceIfNeeded(isPassed, app, submission.id, submission.latestInstance.index, responsibleIndividual))
      _                                                     <- E.liftF(responsibleIndividualVerificationRepository.deleteResponsibleIndividualVerification(cmd.code))
      evts                                                   = asEvents(isPassed, responsibleIndividual, requesterEmail, requesterName)
    } yield (app, evts)
  }

  def processUpdate(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerification: ResponsibleIndividualUpdateVerification): AppCmdResultT = {
    val newResponsibleIndividual = riVerification.responsibleIndividual

    def validateUpdate(): Validated[Failures, ResponsibleIndividual] = {
      Apply[Validated[Failures, *]].map5(
        isStandardNewJourneyApp(app),
        isApproved(app),
        isApplicationIdTheSame(app, riVerification),
        ensureResponsibleIndividualDefined(app),
        isNotCurrentRi(newResponsibleIndividual.fullName.value, newResponsibleIndividual.emailAddress, app)
      ) { case (_, _, _, currentResponsibleIndividual, _) => currentResponsibleIndividual }
    }

    def asEventsUpdate(currentResponsibleIndividual: ResponsibleIndividual): ResponsibleIndividualChanged = {
      ResponsibleIndividualChanged(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = Actors.AppCollaborator(riVerification.requestingAdminEmail),
        previousResponsibleIndividualName = currentResponsibleIndividual.fullName.value,
        previousResponsibleIndividualEmail = currentResponsibleIndividual.emailAddress,
        newResponsibleIndividualName = newResponsibleIndividual.fullName.value,
        newResponsibleIndividualEmail = newResponsibleIndividual.emailAddress,
        submissionId = SubmissionId(riVerification.submissionId.value),
        submissionIndex = riVerification.submissionInstance,
        code = cmd.code,
        requestingAdminName = riVerification.requestingAdminName,
        requestingAdminEmail = riVerification.requestingAdminEmail
      )
    }

    for {
      currentRI <- E.fromEither(validateUpdate().toEither)
      _         <- E.liftF(applicationRepository.updateApplicationChangeResponsibleIndividual(
                     app.id,
                     newResponsibleIndividual.fullName.value,
                     newResponsibleIndividual.emailAddress,
                     cmd.timestamp,
                     submissionId = riVerification.submissionId,
                     submissionIndex = riVerification.submissionInstance
                   ))
      _         <- E.liftF(responsibleIndividualVerificationRepository.deleteResponsibleIndividualVerification(cmd.code))
      evt        = asEventsUpdate(currentRI)
    } yield (app, NonEmptyList.one(evt))
  }

  def process(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther): AppCmdResultT = {
    E.fromEitherF(
      responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(cmd.code)).flatMap {
        case Some(riVerificationToU: ResponsibleIndividualToUVerification)             => processTou(app, cmd, riVerificationToU).value
        case Some(riVerificationTouUplift: ResponsibleIndividualTouUpliftVerification) => processTouUplift(app, cmd, riVerificationTouUplift).value
        case Some(riVerificationUpdate: ResponsibleIndividualUpdateVerification)       => processUpdate(app, cmd, riVerificationUpdate).value
        case _                                                                         => E.leftT(NonEmptyList.one(CommandFailures.GenericFailure(s"No responsibleIndividualVerification found for code ${cmd.code}"))).value
      }
    )
  }
}
