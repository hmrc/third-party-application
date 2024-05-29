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

import uk.gov.hmrc.apiplatform.modules.approvals.services.ApprovalsNamingService
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ResendRequesterEmailVerification
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{CommandFailure, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, TermsOfUseInvitationRepository}

@Singleton
class SubmitApplicationApprovalRequestCommandHandler @Inject() (
    submissionService: SubmissionsService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    approvalsNamingService: ApprovalsNamingService,
    responsibleIndividualVerificationService: ResponsibleIndividualVerificationService,
    applicationService: ApplicationService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, (LaxEmailAddress, String, Submission)]] = {

    def checkSubmission(maybeSubmission: Option[Submission]): Validated[Failures, Submission] = {
      lazy val fails: CommandFailure = GenericFailure(s"No submission found for application ${app.id.value}")
      maybeSubmission.fold(fails.invalidNel[Submission])(_.validNel[CommandFailure])
    }

    submissionService.fetchLatest(app.id).map { maybeSubmission =>
      Apply[Validated[Failures, *]].map6(
        isStandardNewJourneyApp(app),
        isPendingRequesterVerification(app),
        ensureRequesterEmailDefined(app),
        ensureRequesterNameDefined(app),
        ensureVerificationCodeDefined(app),
        checkSubmission(maybeSubmission)
      ) { case (_, _, requesterEmail, requesterName, _, submission) => (requesterEmail, requesterName, submission) }
    }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: ResendRequesterEmailVerification,
      submission: Submission,
      requesterEmail: LaxEmailAddress,
      requesterName: String
    ): (RequesterEmailVerificationResent) = {
    (
      RequesterEmailVerificationResent(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
        submissionId = submission.id,
        submissionIndex = submission.latestInstance.index,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  def process(app: StoredApplication, cmd: ResendRequesterEmailVerification): AppCmdResultT = {
    for {
      validated                                  <- E.fromValidatedF(validate(app))
      (requesterEmail, requesterName, submission) = validated
      emailResent                                 = asEvents(app, cmd, submission, requesterEmail, requesterName)
    } yield (app, NonEmptyList.one(emailResent))
  }
}
