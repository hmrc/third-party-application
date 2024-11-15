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

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, OptionT, Validated}
import cats.syntax.validated._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ResponsibleIndividual, TermsOfUseAcceptance}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.GrantTermsOfUseApproval
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.Status.{Failed, Granted, Warnings}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class GrantTermsOfUseApprovalCommandHandler @Inject() (
    val applicationRepository: ApplicationRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    submissionService: SubmissionsService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends SubmissionApprovalCommandsHandler with ApplicationLogger with ClockNow {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, (Submission, ResponsibleIndividual)]] = {
    (
      for {
        submission <- OptionT(submissionService.fetchLatest(app.id))
      } yield (submission)
    )
      .fold[Validated[Failures, (Submission, ResponsibleIndividual)]](
        GenericFailure(s"No submission found for application ${app.id.value}").invalidNel[(Submission, ResponsibleIndividual)]
      ) {
        case (submission) => {
          Apply[Validated[Failures, *]].map5(
            ensureStandardAccess(app),
            isInProduction(app),
            ensureResponsibleIndividualDefined(app),
            cond(app.state.requestedByEmailAddress.nonEmpty, "No requestedBy email found"),
            cond((submission.status.isGrantedWithWarnings || submission.status.isFailed), "Rejected due to incorrect submission state")
          ) { case (_, _, responsibleIndividual, _, _) => (submission, responsibleIndividual) }
        }
      }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: GrantTermsOfUseApproval,
      submission: Submission
    ): NonEmptyList[ApplicationEvent] = {

    NonEmptyList.of(TermsOfUseApprovalGranted(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
      submissionId = submission.id,
      submissionIndex = submission.latestInstance.index,
      reasons = cmd.reasons,
      escalatedTo = cmd.escalatedTo,
      requestingAdminEmail = app.state.requestedByEmailAddress.get.toLaxEmail,
      requestingAdminName = app.state.requestedByName.getOrElse("UNKNOWN")
    ))

  }

  def process(originalApp: StoredApplication, cmd: GrantTermsOfUseApproval): AppCmdResultT = {
    for {
      validateResult                     <- E.fromValidatedF(validate(originalApp))
      (submission, responsibleIndividual) = validateResult
      updatedSubmission                   = Submission.grant(instant(), cmd.gatekeeperUser, Some(cmd.reasons), cmd.escalatedTo)(submission)
      savedSubmission                    <- E.liftF(submissionService.store(updatedSubmission))
      _                                  <- E.liftF(setTermsOfUseInvitationStatus(originalApp.id, savedSubmission))
      acceptance                          = TermsOfUseAcceptance(responsibleIndividual, instant(), submission.id, submission.latestInstance.index)
      updatedApp                         <- E.liftF(applicationRepository.addApplicationTermsOfUseAcceptance(originalApp.id, acceptance))
      events                              = asEvents(updatedApp, cmd, savedSubmission)
    } yield (updatedApp, events)
  }

  def setTermsOfUseInvitationStatus(applicationId: ApplicationId, submission: Submission): Future[HasSucceeded] = {
    submission.status match {
      case Granted(_, _, _, _) => termsOfUseInvitationRepository.updateState(applicationId, TERMS_OF_USE_V2)
      case Warnings(_, _)      => termsOfUseInvitationRepository.updateState(applicationId, WARNINGS)
      case Failed(_, _)        => termsOfUseInvitationRepository.updateState(applicationId, FAILED)
      case _                   => successful(HasSucceeded)
    }
  }

}
