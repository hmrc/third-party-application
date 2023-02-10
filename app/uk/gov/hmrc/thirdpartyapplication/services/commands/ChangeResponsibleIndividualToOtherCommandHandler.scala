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

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.Apply
import cats.data.{NonEmptyChain, NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationId
}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors

@Singleton
class ChangeResponsibleIndividualToOtherCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    stateHistoryRepository: StateHistoryRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def isNotCurrentRi(name: String, email: String, app: ApplicationData) =
    cond(
      app.access match {
        case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) =>
          !responsibleIndividual.fullName.value.equalsIgnoreCase(name) || !responsibleIndividual.emailAddress.value.equalsIgnoreCase(email)
        case _                                                                                            => true
      },
      s"The specified individual is already the RI for this application"
    )

  private def isApplicationIdTheSame(app: ApplicationData, riVerification: ResponsibleIndividualVerification) =
    cond(app.id == riVerification.applicationId, "The given application id is different")

  import UpdateApplicationEvent._

  def processTou(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerificationToU: ResponsibleIndividualToUVerification): ResultT = {
    def validate(): Validated[CommandFailures, (ResponsibleIndividual, String, String)] = {
      Apply[Validated[CommandFailures, *]].map6(
        isStandardNewJourneyApp(app),
        isPendingResponsibleIndividualVerification(app),
        isApplicationIdTheSame(app, riVerificationToU),
        ensureResponsibleIndividualDefined(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app)
      ) { case (_, _, _, responsibleIndividual, requesterEmail, requesterName) => (responsibleIndividual, requesterEmail, requesterName) }
    }

    def asEvents(responsibleIndividual: ResponsibleIndividual, requesterEmail: String, requesterName: String): (ResponsibleIndividualSet, ApplicationStateChanged) = {
      (
        ResponsibleIndividualSet(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = Actors.AppCollaborator(requesterEmail),
          responsibleIndividualName = responsibleIndividual.fullName.value,
          responsibleIndividualEmail = responsibleIndividual.emailAddress.value,
          submissionId = riVerificationToU.submissionId,
          submissionIndex = riVerificationToU.submissionInstance,
          code = cmd.code,
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        ),
        ApplicationStateChanged(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = Actors.AppCollaborator(requesterEmail),
          app.state.name,
          State.PENDING_GATEKEEPER_APPROVAL,
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        )
      )
    }

    for {
      valid            <- E.fromEither(validate().toEither)
      (riEvt, stateEvt) = asEvents(valid._1, valid._2, valid._3)
      _                <- E.liftF(applicationRepository.updateApplicationSetResponsibleIndividual(
                            app.id,
                            riEvt.responsibleIndividualName,
                            riEvt.responsibleIndividualEmail,
                            riEvt.eventDateTime,
                            riEvt.submissionId,
                            riEvt.submissionIndex
                          ))
      savedApp         <-
        E.liftF(applicationRepository.updateApplicationState(app.id, State.PENDING_GATEKEEPER_APPROVAL, cmd.timestamp, stateEvt.requestingAdminEmail, stateEvt.requestingAdminName))
      _                <- E.liftF(responsibleIndividualVerificationRepository.deleteResponsibleIndividualVerification(riEvt.code))
      _                <- E.liftF(stateHistoryRepository.addStateHistoryRecord(stateEvt))
    } yield (savedApp, NonEmptyList(riEvt, List(stateEvt)))
  }

  def processUpdate(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerification: ResponsibleIndividualUpdateVerification): ResultT = {
    def validateUpdate(): Validated[CommandFailures, ApplicationData] = {
      val responsibleIndividual = riVerification.responsibleIndividual
      Apply[Validated[CommandFailures, *]].map5(
        isStandardNewJourneyApp(app),
        isApproved(app),
        isApplicationIdTheSame(app, riVerification),
        ensureResponsibleIndividualDefined(app),
        isNotCurrentRi(responsibleIndividual.fullName.value, responsibleIndividual.emailAddress.value, app)
      ) { case _ => app }
    }

    def asEventsUpdate(): ResponsibleIndividualChanged = {
      val newResponsibleIndividual      = riVerification.responsibleIndividual
      val previousResponsibleIndividual = getResponsibleIndividual(app).get
      ResponsibleIndividualChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.AppCollaborator(riVerification.requestingAdminEmail),
        newResponsibleIndividualName = newResponsibleIndividual.fullName.value,
        newResponsibleIndividualEmail = newResponsibleIndividual.emailAddress.value,
        previousResponsibleIndividualName = previousResponsibleIndividual.fullName.value,
        previousResponsibleIndividualEmail = previousResponsibleIndividual.emailAddress.value,
        submissionId = riVerification.submissionId,
        submissionIndex = riVerification.submissionInstance,
        code = cmd.code,
        requestingAdminName = riVerification.requestingAdminName,
        requestingAdminEmail = riVerification.requestingAdminEmail
      )
    }

    for {
      valid <- E.fromEither(validateUpdate().toEither)
      evt    = asEventsUpdate()
      _     <- E.liftF(applicationRepository.updateApplicationChangeResponsibleIndividual(
                 app.id,
                 evt.newResponsibleIndividualName,
                 evt.newResponsibleIndividualEmail,
                 evt.eventDateTime,
                 evt.submissionId,
                 evt.submissionIndex
               ))
      _     <- E.liftF(responsibleIndividualVerificationRepository.deleteResponsibleIndividualVerification(evt.code))
    } yield (app, NonEmptyList.one(evt))
  }

  def process(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther): CommandHandler.ResultT = {
    E.fromEitherF(
      responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(cmd.code)).flatMap {
        case Some(riVerificationToU: ResponsibleIndividualToUVerification)       => processTou(app, cmd, riVerificationToU).value
        case Some(riVerificationUpdate: ResponsibleIndividualUpdateVerification) => processUpdate(app, cmd, riVerificationUpdate).value
        case _                                                                   => E.leftT(NonEmptyChain.one(s"No responsibleIndividualVerification found for code ${cmd.code}")).value
      }
    )
  }
}
