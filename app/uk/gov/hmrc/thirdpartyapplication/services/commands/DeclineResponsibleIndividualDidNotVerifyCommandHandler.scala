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
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeclineResponsibleIndividualDidNotVerify, ResponsibleIndividual, State, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._

@Singleton
class DeclineResponsibleIndividualDidNotVerifyCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    stateHistoryRepository: StateHistoryRepository,
    submissionService: SubmissionsService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import CommandFailures._

  private def isApplicationIdTheSame(app: ApplicationData, riVerification: ResponsibleIndividualVerification) =
    cond(app.id == riVerification.applicationId, "The given application id is different")

  def process(app: ApplicationData, cmd: DeclineResponsibleIndividualDidNotVerify, riVerification: ResponsibleIndividualUpdateVerification): ResultT = {

    def validate(): Validated[CommandHandler.Failures, Unit] = {
      Apply[Validated[CommandHandler.Failures, *]].map4(
        isStandardNewJourneyApp(app),
        isApproved(app),
        isApplicationIdTheSame(app, riVerification),
        ensureResponsibleIndividualDefined(app)
      ) { case _ => () }
    }

    def asEvents(): NonEmptyList[ApplicationEvent] = {
      val responsibleIndividual = riVerification.responsibleIndividual

      NonEmptyList.of(
        ResponsibleIndividualDeclinedUpdate(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp.instant,
          actor = Actors.AppCollaborator(riVerification.requestingAdminEmail),
          responsibleIndividualName = responsibleIndividual.fullName.value,
          responsibleIndividualEmail = responsibleIndividual.emailAddress,
          submissionId = SubmissionId(riVerification.submissionId.value),
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
    def validate(): Validated[CommandHandler.Failures, (ResponsibleIndividual, LaxEmailAddress, String)] = {
      Apply[Validated[CommandHandler.Failures, *]].map6(
        isStandardNewJourneyApp(app),
        isPendingResponsibleIndividualVerification(app),
        isApplicationIdTheSame(app, riVerification),
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
      ): (ResponsibleIndividualDidNotVerify, ApplicationApprovalRequestDeclined, ApplicationStateChanged) = {
      (
        ResponsibleIndividualDidNotVerify(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp.instant,
          actor = Actors.AppCollaborator(requesterEmail),
          responsibleIndividualName = responsibleIndividual.fullName.value,
          responsibleIndividualEmail = responsibleIndividual.emailAddress,
          submissionId = SubmissionId(riVerification.submissionId.value),
          submissionIndex = riVerification.submissionInstance,
          code = cmd.code,
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        ),
        ApplicationApprovalRequestDeclined(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp.instant,
          actor = Actors.AppCollaborator(requesterEmail),
          decliningUserName = responsibleIndividual.fullName.value,
          decliningUserEmail = responsibleIndividual.emailAddress,
          submissionId = SubmissionId(riVerification.submissionId.value),
          submissionIndex = riVerification.submissionInstance,
          reasons = "The responsible individual did not accept the terms of use in 20 days.",
          requestingAdminName = requesterName,
          requestingAdminEmail = requesterEmail
        ),
        fromStateHistory(stateHistory, requesterName, requesterEmail)
      )
    }

    for {
      valid                                                             <- E.fromEither(validate().toEither)
      (responsibleIndividual, requestingAdminEmail, requestingAdminName) = valid
      stateHistory                                                       = StateHistory(app.id, State.TESTING, Actors.AppCollaborator(requestingAdminEmail), Some(app.state.name), changedAt = cmd.timestamp)
      _                                                                 <- E.liftF(applicationRepository.updateApplicationState(app.id, State.TESTING, cmd.timestamp, requestingAdminEmail.text, requestingAdminName))
      _                                                                 <- E.liftF(stateHistoryRepository.insert(stateHistory))
      _                                                                 <- E.liftF(responsibleIndividualVerificationRepository.deleteSubmissionInstance(riVerification.submissionId, riVerification.submissionInstance))
      (riEvt, declinedEvt, stateEvt)                                     = asEvents(stateHistory, responsibleIndividual, requestingAdminEmail, requestingAdminName)
      _                                                                 <- E.liftF(submissionService.declineApplicationApprovalRequest(declinedEvt))
    } yield (app, NonEmptyList(riEvt, List(declinedEvt, stateEvt)))
  }

  def process(app: ApplicationData, cmd: DeclineResponsibleIndividualDidNotVerify): ResultT = {
    E.fromEitherF(
      responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(cmd.code)).flatMap(_ match {
        case Some(riVerificationToU: ResponsibleIndividualToUVerification)       => process(app, cmd, riVerificationToU).value
        case Some(riVerificationUpdate: ResponsibleIndividualUpdateVerification) => process(app, cmd, riVerificationUpdate).value
        case _                                                                   => E.leftT(NonEmptyChain.one(GenericFailure(s"No responsibleIndividualVerification found for code ${cmd.code}"))).value
      })
    )
  }
}
