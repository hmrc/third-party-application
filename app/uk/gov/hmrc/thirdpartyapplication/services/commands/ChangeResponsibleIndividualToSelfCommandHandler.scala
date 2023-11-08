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
import cats.data.{NonEmptyList, Validated}
import cats.syntax.validated._

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeResponsibleIndividualToSelf
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{CommandFailure, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access

@Singleton
class ChangeResponsibleIndividualToSelfCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    submissionService: SubmissionsService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import CommandFailures._

  private def isNotCurrentRi(name: String, email: LaxEmailAddress, app: ApplicationData) =
    cond(
      app.access match {
        case Access.Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) =>
          !responsibleIndividual.fullName.value.equalsIgnoreCase(name) || !responsibleIndividual.emailAddress.equalsIgnoreCase(email)
        case _                                                                                            => true
      },
      "The specified individual is already the RI for this application"
    )

  private def validate(app: ApplicationData, cmd: ChangeResponsibleIndividualToSelf): Future[Validated[Failures, Submission]] = {

    def checkSubmission(maybeSubmission: Option[Submission]): Validated[Failures, Submission] = {
      lazy val fails: CommandFailure = GenericFailure(s"No submission found for application ${app.id.value}")

      maybeSubmission.fold(fails.invalidNel[Submission])(_.validNel[CommandFailure])
    }

    submissionService.fetchLatest(app.id).map { maybeSubmission =>
      Apply[Validated[Failures, *]].map6(
        isStandardNewJourneyApp(app),
        isApproved(app),
        isAdminOnApp(cmd.instigator, app),
        ensureResponsibleIndividualDefined(app),
        isNotCurrentRi(cmd.name, cmd.email, app),
        checkSubmission(maybeSubmission)
      ) { case (_, _, _, _, _, submission) => submission }
    }
  }

  private def asEvents(
      app: ApplicationData,
      cmd: ChangeResponsibleIndividualToSelf,
      submission: Submission,
      requesterEmail: LaxEmailAddress,
      requesterName: String
    ): NonEmptyList[ApplicationEvent] = {
    val previousResponsibleIndividual = getResponsibleIndividual(app).get

    NonEmptyList.of(
      ApplicationEvents.ResponsibleIndividualChangedToSelf(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = Actors.AppCollaborator(requesterEmail),
        previousResponsibleIndividualName = previousResponsibleIndividual.fullName.value,
        previousResponsibleIndividualEmail = previousResponsibleIndividual.emailAddress,
        submissionId = SubmissionId(submission.id.value),
        submissionIndex = submission.latestInstance.index,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeResponsibleIndividualToSelf): AppCmdResultT = {

    val requesterName = cmd.name
    for {
      submission    <- E.fromValidatedF(validate(app, cmd))
      requesterEmail = getRequester(app, cmd.instigator)
      savedApp      <- E.liftF(applicationRepository.updateApplicationChangeResponsibleIndividualToSelf(
                         app.id,
                         requesterName,
                         requesterEmail,
                         cmd.timestamp,
                         submission.id,
                         submission.latestInstance.index
                       ))
      events         = asEvents(app, cmd, submission, requesterEmail, requesterName)
    } yield (savedApp, events)
  }
}
