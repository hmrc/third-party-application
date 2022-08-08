/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.Apply
import cats.data.{NonEmptyChain, NonEmptyList, Validated, ValidatedNec}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ChangeResponsibleIndividual, ImportantSubmissionData, Standard, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ChangeResponsibleIndividualCommandHandler @Inject()(
    submissionService: SubmissionsService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def isNotCurrentRi(name: String, email: String, app: ApplicationData) =
    cond(app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) =>
        ! responsibleIndividual.fullName.value.equalsIgnoreCase(name) || ! responsibleIndividual.emailAddress.value.equalsIgnoreCase(email)
      case _ => true
    }, s"The specified individual is already the RI for this application")

  private def validate(app: ApplicationData, cmd: ChangeResponsibleIndividual): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map4(
      isStandardNewJourneyApp(app),
      isApproved(app),
      isAdminOnApp(cmd.instigator, app),
      isNotCurrentRi(cmd.name, cmd.email, app)
    ) { case _ => app }
  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: ChangeResponsibleIndividual, submission: Submission): NonEmptyList[UpdateApplicationEvent] = {
    val requesterEmail = getRequester(app, cmd.instigator)
    NonEmptyList.of(
      ResponsibleIndividualChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        responsibleIndividualName = cmd.name,
        responsibleIndividualEmail = cmd.email,
        submission.id,
        submission.latestInstance.index,
        requestingAdminEmail = getRequester(app, cmd.instigator)
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeResponsibleIndividual): CommandHandler.Result = {
    submissionService.fetchLatest(app.id).map(maybeSubmission => {
      maybeSubmission match {
        case Some(submission) => validate(app, cmd) map { _ =>
          asEvents(app, cmd, submission)
        }
        case _ => Validated.Invalid(NonEmptyChain.one(s"No submission found for application ${app.id}"))
      }
    })
  }
}
