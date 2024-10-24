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
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, OptionT, Validated}
import cats.syntax.validated._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ImportantSubmissionData
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.services.ResponsibleIndividualVerificationService
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SubmitTermsOfUseApproval
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.Status.{Failed, Granted, Warnings}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionDataExtracter
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class SubmitTermsOfUseApprovalCommandHandler @Inject() (
    submissionService: SubmissionsService,
    val applicationRepository: ApplicationRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    responsibleIndividualVerificationService: ResponsibleIndividualVerificationService
  )(implicit val ec: ExecutionContext
  ) extends SubmissionApprovalCommandsHandler with ApplicationLogger {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, Submission]] = {
    (
      for {
        submission <- OptionT(submissionService.fetchLatest(app.id))
        termsOfUse <- OptionT(termsOfUseInvitationRepository.fetch(app.id))
      } yield (submission, termsOfUse)
    )
      .fold[Validated[Failures, Submission]](
        GenericFailure(s"No submission/termsOfUseInvitation found for application ${app.id.value}").invalidNel[(Submission)]
      ) {
        case (submission, _) => {
          Apply[Validated[Failures, *]].map3(
            ensureStandardAccess(app),
            isInProduction(app),
            cond(submission.status.isAnsweredCompletely, "Submission has not been answered completely")
          ) { case _ => submission }
        }
      }
  }

  def process(app: StoredApplication, cmd: SubmitTermsOfUseApproval): AppCmdResultT = {
    import SubmissionDataExtracter._

    logStartingApprovalRequestProcessing(app.id)

    for {
      submission                         <- E.fromValidatedF(validate(app))
      isRequesterTheResponsibleIndividual = SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission)
      importantSubmissionData             = getImportantSubmissionData(submission, cmd.requesterName, cmd.requesterEmail.text).get // Safe at this point
      savedApp                           <- E.liftF(applicationRepository.updateApplicationImportantSubmissionData(app.id, importantSubmissionData))
      submittedSubmission                 = Submission.submit(cmd.timestamp, cmd.requesterEmail.text)(submission)
      _                                   = logCompletedApprovalRequest(savedApp)
      result                             <- if (isRequesterTheResponsibleIndividual) handleRequesterIsTheResponsibleIndividual(app, submittedSubmission, cmd)
                                            else handleResponsibleIndividualNotRequester(app, importantSubmissionData, submittedSubmission, cmd)

    } yield result
  }

  private def handleResponsibleIndividualNotRequester(
      app: StoredApplication,
      importantSubmissionData: ImportantSubmissionData,
      submission: Submission,
      cmd: SubmitTermsOfUseApproval
    ): AppCmdResultT = {

    def asEvents(
        verificationId: ResponsibleIndividualVerificationId,
        importantSubmissionData: ImportantSubmissionData
      ): NonEmptyList[ApplicationEvent] = {
      NonEmptyList.of(
        TermsOfUseApprovalSubmitted(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = cmd.actor,
          submissionId = submission.id,
          submissionIndex = submission.latestInstance.index,
          requestingAdminName = cmd.requesterName,
          requestingAdminEmail = cmd.requesterEmail
        ),
        ResponsibleIndividualVerificationStarted(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = cmd.actor,
          applicationName = app.name.value,
          requestingAdminName = cmd.requesterName,
          requestingAdminEmail = cmd.requesterEmail,
          responsibleIndividualName = importantSubmissionData.responsibleIndividual.fullName.value,
          responsibleIndividualEmail = importantSubmissionData.responsibleIndividual.emailAddress,
          submissionId = submission.id,
          submissionIndex = submission.latestInstance.index,
          verificationId = verificationId.value
        )
      )

    }
    for {
      updatedSubmission <- E.liftF(successful(Submission.pendingResponsibleIndividual(cmd.timestamp, cmd.requesterEmail.text)(submission)))
      savedSubmission   <- E.liftF(submissionService.store(updatedSubmission))
      _                 <- E.liftF(setTermsOfUseInvitationStatus(app.id, savedSubmission))
      verification      <- E.liftF(responsibleIndividualVerificationService.createNewTouUpliftVerification(
                             app,
                             submission.id,
                             submission.latestInstance.index,
                             cmd.requesterName,
                             cmd.requesterEmail
                           ))
      events             = asEvents(verification.id, importantSubmissionData)
    } yield (app, events)
  }

  private def handleRequesterIsTheResponsibleIndividual(app: StoredApplication, submission: Submission, cmd: SubmitTermsOfUseApproval): AppCmdResultT = {

    def asEvents(submissionIsGranted: Boolean): NonEmptyList[ApplicationEvent] = {
      val standardEvents = NonEmptyList.of(TermsOfUseApprovalSubmitted(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        submissionId = submission.id,
        submissionIndex = submission.latestInstance.index,
        requestingAdminName = cmd.requesterName,
        requestingAdminEmail = cmd.requesterEmail
      ))

      if (submissionIsGranted) {
        standardEvents ++ List(TermsOfUsePassed(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = cmd.actor,
          submissionId = submission.id,
          submissionIndex = submission.latestInstance.index
        ))
      } else standardEvents

    }
    for {
      updatedSubmission <- E.liftF(successful(Submission.automaticallyMark(cmd.timestamp, cmd.requesterEmail.text)(submission)))
      savedSubmission   <- E.liftF(submissionService.store(updatedSubmission))
      mayBeUpdatedApp   <- E.liftF(addTouAcceptanceIfNeeded(savedSubmission.status.isGranted, app, submission, cmd.timestamp, cmd.requesterName, cmd.requesterEmail))
      _                 <- E.liftF(setTermsOfUseInvitationStatus(app.id, savedSubmission))
      events             = asEvents(savedSubmission.status.isGranted)
    } yield (mayBeUpdatedApp, events)
  }

  def setTermsOfUseInvitationStatus(applicationId: ApplicationId, submission: Submission): Future[HasSucceeded] = {
    submission.status match {
      case Granted(_, _, _, _) => termsOfUseInvitationRepository.updateState(applicationId, TERMS_OF_USE_V2)
      case Warnings(_, _)      => termsOfUseInvitationRepository.updateState(applicationId, WARNINGS)
      case Failed(_, _)        => termsOfUseInvitationRepository.updateState(applicationId, FAILED)
      case _                   => successful(HasSucceeded)
    }
  }

  private def logStartingApprovalRequestProcessing(applicationId: ApplicationId): Unit = {
    logger.info(s"Approval-01: terms of use request made for appId:${applicationId}")
  }

  private def logCompletedApprovalRequest(app: StoredApplication): Unit =
    logger.info(s"Approval-02: terms of use request application:${app.name} appId:${app.id} appState:${app.state.name}")

}
