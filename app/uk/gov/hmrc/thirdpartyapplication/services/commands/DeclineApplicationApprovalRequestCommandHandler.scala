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
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, Validated, ValidatedNec}
import cats.syntax.validated._

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeclineApplicationApprovalRequest, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}

@Singleton
class DeclineApplicationApprovalRequestCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    submissionService: SubmissionsService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData): Future[ValidatedNec[String, (String, String, Submission)]] = {

    def checkSubmission(maybeSubmission: Option[Submission]): Validated[CommandFailures, Submission] =
      maybeSubmission.fold(s"No submission found for application ${app.id.value}".invalidNec[Submission])(_.validNec[String])

    submissionService.fetchLatest(app.id).map { maybeSubmission =>
      Apply[Validated[CommandFailures, *]].map5(
        isStandardNewJourneyApp(app),
        isInPendingGatekeeperApprovalOrResponsibleIndividualVerification(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app),
        checkSubmission(maybeSubmission)
      ) { case (_, _, requesterEmail, requesterName, submission) => (requesterEmail, requesterName, submission) }
    }
  }

  import UpdateApplicationEvent._

  private def asEvents(
      app: ApplicationData,
      cmd: DeclineApplicationApprovalRequest,
      submission: Submission,
      requesterEmail: String,
      requesterName: String
    ): (ApplicationApprovalRequestDeclined, ApplicationStateChanged) = {

    (
      ApplicationApprovalRequestDeclined(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = GatekeeperUserActor(cmd.gatekeeperUser),
        decliningUserName = cmd.gatekeeperUser,
        decliningUserEmail = cmd.gatekeeperUser,
        submissionId = submission.id,
        submissionIndex = submission.latestInstance.index,
        reasons = cmd.reasons,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = GatekeeperUserActor(cmd.gatekeeperUser),
        app.state.name,
        State.TESTING,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: DeclineApplicationApprovalRequest): CommandHandler.ResultT = {
    for {
      validated                                  <- E.fromValidatedF(validate(app))
      (requesterEmail, requesterName, submission) = validated
      savedApp                                   <- E.liftF(applicationRepository.updateApplicationState(app.id, State.TESTING, cmd.timestamp, requesterEmail, requesterName))
      (approvalDeclined, stateChange)             = asEvents(savedApp, cmd, submission, requesterEmail, requesterName)
      events                                      = NonEmptyList.of(approvalDeclined, stateChange)
      _                                          <- E.liftF(stateHistoryRepository.applyEvents(events))
      _                                          <- E.liftF(submissionService.declineApplicationApprovalRequest(approvalDeclined))
    } yield (savedApp, events)
  }
}
