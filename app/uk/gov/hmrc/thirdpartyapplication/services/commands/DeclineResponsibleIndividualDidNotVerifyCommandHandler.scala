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
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeclineResponsibleIndividualDidNotVerify, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.ResponsibleIndividual

@Singleton
class DeclineResponsibleIndividualDidNotVerifyCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    stateHistoryRepository: StateHistoryRepository,
    submissionService: SubmissionsService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import UpdateApplicationEvent._

  private def isApplicationIdTheSame(app: ApplicationData, riVerification: ResponsibleIndividualVerification) =
    cond(app.id == riVerification.applicationId, "The given application id is different")

  def process(app: ApplicationData, cmd: DeclineResponsibleIndividualDidNotVerify, riVerification: ResponsibleIndividualUpdateVerification): ResultT = {

    def validate(): Validated[CommandFailures, Unit] = {
      Apply[Validated[CommandFailures, *]].map4(
        isStandardNewJourneyApp(app),
        isApproved(app),
        isApplicationIdTheSame(app, riVerification),
        ensureResponsibleIndividualDefined(app)
      ) { case _ => () }
    }

    def asEvents(): NonEmptyList[UpdateApplicationEvent] = {
      val responsibleIndividual = riVerification.responsibleIndividual

      NonEmptyList.of(
        ResponsibleIndividualDeclinedUpdate(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = CollaboratorActor(riVerification.requestingAdminEmail),
          responsibleIndividualName = responsibleIndividual.fullName.value,
          responsibleIndividualEmail = responsibleIndividual.emailAddress.value,
          submissionId = riVerification.submissionId,
          submissionIndex = riVerification.submissionInstance,
          code = cmd.code,
          requestingAdminName = riVerification.requestingAdminName,
          requestingAdminEmail = riVerification.requestingAdminEmail
        )
      )
    }

    for {
      _     <- E.fromEither(validate().toEither)
      events = asEvents()
      _     <- E.liftF(responsibleIndividualVerificationRepository.deleteSubmissionInstance(riVerification.submissionId, riVerification.submissionInstance))
    } yield (app, events)
  }

  def process(app: ApplicationData, cmd: DeclineResponsibleIndividualDidNotVerify, riVerification: ResponsibleIndividualToUVerification): ResultT = {
    def validate(): Validated[CommandFailures, (ResponsibleIndividual, String, String)] = {
      Apply[Validated[CommandFailures, *]].map6(
        isStandardNewJourneyApp(app),
        isPendingResponsibleIndividualVerification(app),
        isApplicationIdTheSame(app, riVerification),
        ensureResponsibleIndividualDefined(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app)
      ) { case (_, _, _, responsibleIndividual, requesterEmail, requesterName) => (responsibleIndividual, requesterEmail, requesterName) }
    }

    def asEvents(
        responsibleIndividual: ResponsibleIndividual,
        requesterEmail: String,
        requesterName: String
      ): (ResponsibleIndividualDidNotVerify, ApplicationApprovalRequestDeclined, ApplicationStateChanged) = {
      (
        ResponsibleIndividualDidNotVerify(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = CollaboratorActor(requesterEmail),
          responsibleIndividualName = responsibleIndividual.fullName.value,
          responsibleIndividualEmail = responsibleIndividual.emailAddress.value,
          submissionId = riVerification.submissionId,
          submissionIndex = riVerification.submissionInstance,
          code = cmd.code,
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        ),
        ApplicationApprovalRequestDeclined(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = CollaboratorActor(requesterEmail),
          decliningUserName = responsibleIndividual.fullName.value,
          decliningUserEmail = responsibleIndividual.emailAddress.value,
          submissionId = riVerification.submissionId,
          submissionIndex = riVerification.submissionInstance,
          reasons = "The responsible individual did not accept the terms of use in 20 days.",
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        ),
        ApplicationStateChanged(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = CollaboratorActor(requesterEmail),
          app.state.name,
          State.TESTING,
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        )
      )
    }

    for {
      valid                                                             <- E.fromEither(validate().toEither)
      (responsibleIndividual, requestingAdminEmail, requestingAdminName) = valid
      (riEvt, declinedEvt, stateEvt)                                     = asEvents(responsibleIndividual, requestingAdminEmail, requestingAdminName)
      _                                                                 <- E.liftF(applicationRepository.updateApplicationState(app.id, stateEvt.newAppState, stateEvt.eventDateTime, stateEvt.requestingAdminEmail, stateEvt.requestingAdminName))
      _                                                                 <- E.liftF(stateHistoryRepository.addStateHistoryRecord(stateEvt))
      _                                                                 <- E.liftF(responsibleIndividualVerificationRepository.deleteSubmissionInstance(riVerification.submissionId, riVerification.submissionInstance))
      _                                                                 <- E.liftF(submissionService.declineApplicationApprovalRequest(declinedEvt))
    } yield (app, NonEmptyList(riEvt, List(declinedEvt, stateEvt)))
  }

  def process(app: ApplicationData, cmd: DeclineResponsibleIndividualDidNotVerify): ResultT = {
    E.fromEitherF(
      responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(cmd.code)).flatMap(_ match {
        case Some(riVerificationToU: ResponsibleIndividualToUVerification)       => process(app, cmd, riVerificationToU).value
        case Some(riVerificationUpdate: ResponsibleIndividualUpdateVerification) => process(app, cmd, riVerificationUpdate).value
        case _                                                                   => E.leftT(NonEmptyChain.one(s"No responsibleIndividualVerification found for code ${cmd.code}")).value
      })
    )
  }
}
