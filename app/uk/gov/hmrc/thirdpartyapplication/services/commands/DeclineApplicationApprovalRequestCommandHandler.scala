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
import cats.data.{NonEmptyChain, NonEmptyList, Validated, ValidatedNec}

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeclineApplicationApprovalRequest, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class DeclineApplicationApprovalRequestCommandHandler @Inject() (
    submissionService: SubmissionsService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: DeclineApplicationApprovalRequest): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map4(
      isStandardNewJourneyApp(app),
      isInPendingGatekeeperApprovalOrResponsibleIndividualVerification(app),
      isRequesterEmailDefined(app),
      isRequesterNameDefined(app)
    ) { case _ => app }
  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: DeclineApplicationApprovalRequest, submission: Submission): NonEmptyList[UpdateApplicationEvent] = {
    val requesterEmail = getRequesterEmail(app).get
    val requesterName  = getRequesterName(app).get
    NonEmptyList.of(
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

  def process(app: ApplicationData, cmd: DeclineApplicationApprovalRequest): CommandHandler.Result = {
    submissionService.fetchLatest(app.id).map(maybeSubmission => {
      maybeSubmission match {
        case Some(submission) => validate(app, cmd) map { _ =>
            asEvents(app, cmd, submission)
          }
        case _                => Validated.Invalid(NonEmptyChain.one(s"No submission found for application ${app.id}"))
      }
    })
  }
}
