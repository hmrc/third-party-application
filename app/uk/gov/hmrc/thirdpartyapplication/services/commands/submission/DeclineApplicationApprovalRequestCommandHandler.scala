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

package uk.gov.hmrc.thirdpartyapplication.services.commands.submission

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, Validated}
import cats.syntax.validated._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeclineApplicationApprovalRequest
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{CommandFailure, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class DeclineApplicationApprovalRequestCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    submissionService: SubmissionsService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, (LaxEmailAddress, String, Submission)]] = {

    def checkSubmission(maybeSubmission: Option[Submission]): Validated[Failures, Submission] = {
      val fails: CommandFailure = GenericFailure(s"No submission found for application ${app.id.value}")
      maybeSubmission.fold(fails.invalidNel[Submission])(_.validNel[CommandFailure])
    }

    submissionService.fetchLatest(app.id).map { maybeSubmission =>
      Apply[Validated[Failures, *]].map5(
        isStandardNewJourneyApp(app),
        isInPendingGatekeeperApprovalOrResponsibleIndividualVerification(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app),
        checkSubmission(maybeSubmission)
      ) { case (_, _, requesterEmail, requesterName, submission) => (requesterEmail, requesterName, submission) }
    }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: DeclineApplicationApprovalRequest,
      submission: Submission,
      requesterEmail: LaxEmailAddress,
      requesterName: String,
      stateHistory: StateHistory
    ): (ApplicationEvents.ApplicationApprovalRequestDeclined, ApplicationEvents.ApplicationStateChanged) = {

    (
      ApplicationEvents.ApplicationApprovalRequestDeclined(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
        decliningUserName = cmd.gatekeeperUser,
        decliningUserEmail = LaxEmailAddress(cmd.gatekeeperUser), // Not nice but we have nothing better
        submissionId = SubmissionId(submission.id.value),
        submissionIndex = submission.latestInstance.index,
        reasons = cmd.reasons,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      ),
      fromStateHistory(stateHistory, requesterName, requesterEmail)
    )
  }

  def process(app: StoredApplication, cmd: DeclineApplicationApprovalRequest): AppCmdResultT = {

    val stateHistory = StateHistory(
      applicationId = app.id,
      state = State.TESTING,
      previousState = Some(app.state.name),
      actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
      changedAt = cmd.timestamp
    )

    for {
      validated                                  <- E.fromValidatedF(validate(app))
      (requesterEmail, requesterName, submission) = validated
      savedApp                                   <- E.liftF(applicationRepository.updateApplicationState(app.id, State.TESTING, cmd.timestamp, requesterEmail.text, requesterName))
      _                                          <- E.liftF(stateHistoryRepository.insert(stateHistory))
      (approvalDeclined, stateChange)             = asEvents(savedApp, cmd, submission, requesterEmail, requesterName, stateHistory)
      _                                          <- E.liftF(submissionService.declineApplicationApprovalRequest(approvalDeclined))
      events                                      = NonEmptyList.of(approvalDeclined, stateChange)
    } yield (savedApp, events)
  }
}
