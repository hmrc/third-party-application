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

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ImportantSubmissionData, Standard, VerifyResponsibleIndividual}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._


@Singleton
class VerifyResponsibleIndividualCommandHandler @Inject() (
    submissionService: SubmissionsService,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def isNotCurrentRi(name: String, email: LaxEmailAddress, app: ApplicationData) =
    cond(
      app.access match {
        case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) =>
          !responsibleIndividual.fullName.value.equalsIgnoreCase(name) || !responsibleIndividual.emailAddress.equalsIgnoreCase(email)
        case _                                                                                            => true
      },
      s"The specified individual is already the RI for this application"
    )

  private def validate(app: ApplicationData, cmd: VerifyResponsibleIndividual): Validated[CommandFailures, Collaborator] = {
    Apply[Validated[CommandFailures, *]].map5(
      isStandardNewJourneyApp(app),
      isApproved(app),
      isAdminOnApp(cmd.instigator, app),
      isNotCurrentRi(cmd.riName, cmd.riEmail, app),
      ensureRequesterEmailDefined(app)
    ) { case (_, _, instigator, _, _) => instigator }
  }

  private def asEvents(app: ApplicationData, cmd: VerifyResponsibleIndividual, submission: Submission, requesterEmail: LaxEmailAddress): NonEmptyList[AbstractApplicationEvent] = {
    NonEmptyList.of(
      ResponsibleIndividualVerificationStarted(
        id = EventId.random,
        applicationId = app.id,
        app.name,
        eventDateTime = cmd.timestamp.instant,
        actor = Actors.AppCollaborator(requesterEmail),
        cmd.requesterName,
        requestingAdminEmail = getRequester(app, cmd.instigator),
        responsibleIndividualName = cmd.riName,
        responsibleIndividualEmail = cmd.riEmail,
        SubmissionId(submission.id.value),
        submission.latestInstance.index,
        ResponsibleIndividualVerificationId.random.value
      )
    )
  }

  def process(app: ApplicationData, cmd: VerifyResponsibleIndividual): ResultT = {
    lazy val noSubmission = NonEmptyChain.one(s"No submission found for application ${app.id}")

    for {
      submission <- E.fromOptionF(submissionService.fetchLatest(app.id), noSubmission)
      instigator <- E.fromEither(validate(app, cmd).toEither)
      events      = asEvents(app, cmd, submission, instigator.emailAddress)
      _          <- E.liftF(responsibleIndividualVerificationRepository.applyEvents(events))
    } yield (app, events)
  }
}
