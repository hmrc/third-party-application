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
import cats.data.{NonEmptyChain, NonEmptyList, Validated}

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationId
}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandFailures.GenericFailure

@Singleton
class ChangeResponsibleIndividualToOtherCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    stateHistoryRepository: StateHistoryRepository,
    submissionsService: SubmissionsService,
    emailConnector: EmailConnector,
    clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import CommandFailures._

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

  def processTou(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerificationToU: ResponsibleIndividualToUVerification): ResultT = {
    def validate(): Validated[CommandHandler.Failures, (ResponsibleIndividual, LaxEmailAddress, String)] = {
      Apply[Validated[CommandHandler.Failures, *]].map6(
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

  def processTouUplift(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerificationToU: ResponsibleIndividualTouUpliftVerification)(implicit hc: HeaderCarrier): ResultT = {
    def validate(): Validated[CommandHandler.Failures, (ResponsibleIndividual, LaxEmailAddress, String)] = {
      Apply[Validated[CommandHandler.Failures, *]].map6(
        isStandardNewJourneyApp(app),
        isInProduction(app),
        isApplicationIdTheSame(app, riVerificationToU),
        ensureResponsibleIndividualDefined(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app)
      ) { case (_, _, _, responsibleIndividual, requesterEmail, requesterName) => (responsibleIndividual, requesterEmail, requesterName) }
    }

    def asEvents(
        responsibleIndividual: ResponsibleIndividual,
        requesterEmail: LaxEmailAddress,
        requesterName: String
      ): (ResponsibleIndividualSet) = {
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
          val acceptance            = TermsOfUseAcceptance(responsibleIndividual, LocalDateTime.now(clock), submissionId, submissionInstance)
          applicationRepository.addApplicationTermsOfUseAcceptance(appWithoutTouAcceptance.id, acceptance)
        } else {
          Future.successful(appWithoutTouAcceptance)
        }
    }

    def sendConfirmationEmailIfNeeded(
        addTouAcceptance: Boolean,
        application: ApplicationData
      )(implicit hc: HeaderCarrier
      ): Future[HasSucceeded] = {
      if (addTouAcceptance) {
        emailConnector.sendNewTermsOfUseConfirmation(application.name, application.admins.map(_.emailAddress))
      } else {
        Future.successful(HasSucceeded)
      }
    }

    for {
      valid                                                 <- E.fromEither(validate().toEither)
      (responsibleIndividual, requesterEmail, requesterName) = valid
      submission                                            <- E.fromOptionF(submissionsService.markSubmission(app.id, requesterEmail.toString()), NonEmptyChain.one(GenericFailure("Submission not found")))
      isPassed                                               = submission.status.isGranted
      _                                                     <- E.liftF(addTouAcceptanceIfNeeded(isPassed, app, riVerificationToU.submissionId, riVerificationToU.submissionInstance, responsibleIndividual))
      _                                                     <- E.liftF(sendConfirmationEmailIfNeeded(isPassed, app))
      _                                                     <- E.liftF(responsibleIndividualVerificationRepository.deleteResponsibleIndividualVerification(cmd.code))
      evt                                                    = asEvents(responsibleIndividual, requesterEmail, requesterName)
    } yield (app, NonEmptyList.one(evt))
  }

  def processUpdate(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerification: ResponsibleIndividualUpdateVerification): ResultT = {
    val newResponsibleIndividual = riVerification.responsibleIndividual

    def validateUpdate(): Validated[CommandHandler.Failures, ResponsibleIndividual] = {
      Apply[Validated[CommandHandler.Failures, *]].map5(
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

  def process(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther)(implicit hc: HeaderCarrier): CommandHandler.ResultT = {
    E.fromEitherF(
      responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(cmd.code)).flatMap {
        case Some(riVerificationToU: ResponsibleIndividualToUVerification)             => processTou(app, cmd, riVerificationToU).value
        case Some(riVerificationTouUplift: ResponsibleIndividualTouUpliftVerification) => processTouUplift(app, cmd, riVerificationTouUplift).value
        case Some(riVerificationUpdate: ResponsibleIndividualUpdateVerification)       => processUpdate(app, cmd, riVerificationUpdate).value
        case _                                                                         => E.leftT(NonEmptyChain.one(GenericFailure(s"No responsibleIndividualVerification found for code ${cmd.code}"))).value
      }
    )
  }
}
