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

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, OptionT, Validated}
import cats.syntax.validated._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ImportantSubmissionData
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.GrantApplicationApprovalRequest
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class GrantApplicationApprovalRequestCommandHandler @Inject() (
    val applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    submissionService: SubmissionsService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends SubmissionApprovalCommandsHandler with ApplicationLogger with ClockNow {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, (Submission, ImportantSubmissionData)]] = {
    (
      for {
        submission              <- OptionT(submissionService.fetchLatest(app.id))
        importantSubmissionData <- OptionT(successful(app.importantSubmissionData))
      } yield (submission, importantSubmissionData)
    )
      .fold[Validated[Failures, (Submission, ImportantSubmissionData)]](
        GenericFailure(s"No submission or important submission data found for application ${app.id.value}").invalidNel[(Submission, ImportantSubmissionData)]
      ) {
        case (submission, importantSubmissionData) => {
          Apply[Validated[Failures, *]].map5(
            ensureStandardAccess(app),
            isInPendingGatekeeperApproval(app),
            cond(app.state.requestedByEmailAddress.nonEmpty, "No requestedBy email found"),
            cond(app.state.requestedByName.nonEmpty, "No requestedBy name found"),
            cond(submission.status.isSubmitted, "Submission has not been submitted")
          ) { case _ => (submission, importantSubmissionData) }
        }
      }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: GrantApplicationApprovalRequest,
      submission: Submission
    ): NonEmptyList[ApplicationEvent] = {
    cmd.warnings.fold[NonEmptyList[ApplicationEvent]](
      NonEmptyList.of(ApplicationApprovalRequestGranted(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
        submissionId = submission.id,
        submissionIndex = submission.latestInstance.index,
        requestingAdminEmail = app.state.requestedByEmailAddress.get.toLaxEmail,
        requestingAdminName = app.state.requestedByName.get
      ))
    )(warning =>
      NonEmptyList.of(ApplicationApprovalRequestGrantedWithWarnings(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
        submissionId = submission.id,
        submissionIndex = submission.latestInstance.index,
        requestingAdminEmail = app.state.requestedByEmailAddress.get.toLaxEmail,
        requestingAdminName = app.state.requestedByName.get,
        warnings = warning,
        escalatedTo = cmd.escalatedTo
      ))
    )

  }

  def process(originalApp: StoredApplication, cmd: GrantApplicationApprovalRequest): AppCmdResultT = {
    logStartingApprovalRequestProcessing(originalApp.id)

    for {
      validateResult                       <- E.fromValidatedF(validate(originalApp))
      (submission, importantSubmissionData) = validateResult
      // Set application state to user verification
      updatedApp                            = grantApp(originalApp)
      savedApp                             <- E.liftF(applicationRepository.save(updatedApp))
      _                                    <- E.liftF(stateHistoryRepository.insert(createStateHistory(savedApp, State.PENDING_GATEKEEPER_APPROVAL, Actors.GatekeeperUser(cmd.gatekeeperUser), cmd.timestamp)))
      updatedSubmission                     = grantSubmission(cmd.gatekeeperUser, cmd.warnings, cmd.escalatedTo)(submission)
      savedSubmission                      <- E.liftF(submissionService.store(updatedSubmission))
      events                                = asEvents(savedApp, cmd, savedSubmission)
      _                                     = logCompletedApprovalRequest(savedApp, savedSubmission)
    } yield (updatedApp, events)
  }

  private def grantApp(application: StoredApplication): StoredApplication = {
    application.copy(state = application.state.toPendingRequesterVerification(instant()))
  }

  private def grantSubmission(gatekeeperUserName: String, warnings: Option[String], escalatedTo: Option[String])(submission: Submission) = {
    warnings.fold(
      Submission.grant(Instant.now(clock), gatekeeperUserName, None, None)(submission)
    )(value =>
      Submission.grantWithWarnings(Instant.now(clock), gatekeeperUserName, value, escalatedTo)(submission)
    )
  }

  private def logStartingApprovalRequestProcessing(applicationId: ApplicationId): Unit = {
    s"Granted-01: grant appId:${applicationId}"
  }

  private def logCompletedApprovalRequest(app: StoredApplication, submission: Submission) =
    logger.info(s"Granted-02: grant appId:${app.id} ${app.state.name} ${submission.status.getClass.getSimpleName}")

}
