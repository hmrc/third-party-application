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
import cats.data.{NonEmptyList, OptionT, Validated}
import cats.syntax.validated._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ImportantSubmissionData, ResponsibleIndividual, TermsOfUseAcceptance}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.services.{ApprovalsNamingService, ResponsibleIndividualVerificationService}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SubmitApplicationApprovalRequest
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionDataExtracter
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, _}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService.noExclusions
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class SubmitApplicationApprovalRequestCommandHandler @Inject() (
    submissionService: SubmissionsService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    approvalsNamingService: ApprovalsNamingService,
    responsibleIndividualVerificationService: ResponsibleIndividualVerificationService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler with ApplicationLogger {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, (Submission, String)]] = {
    (
      for {
        submission     <- OptionT(submissionService.fetchLatest(app.id))
        appName        <- OptionT.fromOption[Future](SubmissionDataExtracter.getApplicationName(submission))
        nameValidation <- OptionT.liftF[Future, ApplicationNameValidationResult](approvalsNamingService.validateApplicationName(appName, noExclusions))
      } yield (submission, appName, nameValidation)
    )
      .fold[Validated[Failures, (Submission, String)]](
        GenericFailure(s"No submission found for application ${app.id.value}").invalidNel[(Submission, String)]
      ) {
        case (submission, appName, nameValidationResult) => {
          Apply[Validated[Failures, *]].map5(
            isStandardNewJourneyApp(app),
            isInTesting(app),
            cond(submission.status.isAnsweredCompletely, "Submission has not been answered completely"),
            cond(nameValidationResult != DuplicateName, "New name is a duplicate"),
            cond(nameValidationResult != InvalidName, "New name is invalid")
          ) { case _ => (submission, appName) }
        }
      }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: SubmitApplicationApprovalRequest,
      submission: Submission,
      isRequesterTheResponsibleIndividual: Boolean,
      verificationId: Option[ResponsibleIndividualVerificationId],
      importantSubmissionData: ImportantSubmissionData
    ): NonEmptyList[ApplicationEvent] = {
    val submittedEvent = ApplicationApprovalRequestSubmitted(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = cmd.actor,
      submissionId = submission.id,
      submissionIndex = submission.latestInstance.index,
      requestingAdminName = cmd.requesterName,
      requestingAdminEmail = cmd.requesterEmail
    )

    if (isRequesterTheResponsibleIndividual) {
      NonEmptyList.one(
        submittedEvent
      )
    } else {
      NonEmptyList.of(
        submittedEvent,
        ResponsibleIndividualVerificationRequired(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = cmd.actor,
          applicationName = app.name,
          requestingAdminName = cmd.requesterName,
          requestingAdminEmail = cmd.requesterEmail,
          responsibleIndividualName = importantSubmissionData.responsibleIndividual.fullName.value,
          responsibleIndividualEmail = importantSubmissionData.responsibleIndividual.emailAddress,
          submissionId = submission.id,
          submissionIndex = submission.latestInstance.index,
          verificationId = verificationId.get.value
        )
      )
    }
  }

  def process(app: StoredApplication, cmd: SubmitApplicationApprovalRequest): AppCmdResultT = {
    import SubmissionDataExtracter._

    logStartingApprovalRequestProcessing(app.id)

    for {
      validated                          <- E.fromValidatedF(validate(app))
      (submission, appName)               = validated
      isRequesterTheResponsibleIndividual = SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission)
      importantSubmissionData             = getImportantSubmissionData(submission, cmd.requesterName, cmd.requesterEmail.text).get // Safe at this point
      updatedApp                          = deriveNewAppDetails(app, isRequesterTheResponsibleIndividual, appName, importantSubmissionData, cmd)
      savedApp                           <- E.liftF(applicationRepository.save(updatedApp))
      _                                  <- E.liftF(addTouAcceptanceIfNeeded(isRequesterTheResponsibleIndividual, updatedApp, submission, cmd))
      _                                  <- E.liftF(stateHistoryRepository.insert(createStateHistory(savedApp, cmd)))
      updatedSubmission                   = Submission.submit(cmd.timestamp, cmd.requesterEmail.text)(submission)
      savedSubmission                    <- E.liftF(submissionService.store(updatedSubmission))
      verificationId                     <- E.liftF(createTouUpliftVerificationRecordIfNeeded(isRequesterTheResponsibleIndividual, savedApp, submission, cmd))
      _                                   = logCompletedApprovalRequest(savedApp)
      events                              = asEvents(app, cmd, submission, isRequesterTheResponsibleIndividual, verificationId, importantSubmissionData)
    } yield (app, events)
  }

  private def logStartingApprovalRequestProcessing(applicationId: ApplicationId) = {
    logger.info(s"Approval-01: approval request made for appId:${applicationId}")
  }

  private def logCompletedApprovalRequest(app: StoredApplication) =
    logger.info(s"Approval-02: approval request (pending) application:${app.name} appId:${app.id} appState:${app.state.name}")

  private def deriveNewAppDetails(
      existing: StoredApplication,
      isRequesterTheResponsibleIndividual: Boolean,
      applicationName: String,
      importantSubmissionData: ImportantSubmissionData,
      cmd: SubmitApplicationApprovalRequest
    ): StoredApplication =
    existing.copy(
      name = applicationName,
      normalisedName = applicationName.toLowerCase,
      access = updateStandardData(existing.access, importantSubmissionData),
      state = if (isRequesterTheResponsibleIndividual) {
        existing.state.toPendingGatekeeperApproval(cmd.requesterEmail.text, cmd.requesterName, cmd.timestamp)
      } else {
        existing.state.toPendingResponsibleIndividualVerification(cmd.requesterEmail.text, cmd.requesterName, cmd.timestamp)
      }
    )

  private def updateStandardData(existingAccess: Access, importantSubmissionData: ImportantSubmissionData): Access = {
    existingAccess match {
      case s: Access.Standard => s.copy(importantSubmissionData = Some(importantSubmissionData))
      case _                  => existingAccess
    }
  }

  private def createStateHistory(snapshotApp: StoredApplication, cmd: SubmitApplicationApprovalRequest): StateHistory =
    StateHistory(
      snapshotApp.id,
      snapshotApp.state.name,
      Actors.AppCollaborator(cmd.requesterEmail),
      Some(State.TESTING),
      None,
      cmd.timestamp
    )

  private def addTouAcceptanceIfNeeded(
      addTouAcceptance: Boolean,
      appWithoutTouAcceptance: StoredApplication,
      submission: Submission,
      cmd: SubmitApplicationApprovalRequest
    ): Future[StoredApplication] = {
    if (addTouAcceptance) {
      val responsibleIndividual = ResponsibleIndividual.build(cmd.requesterName, cmd.requesterEmail.text)
      val acceptance            = TermsOfUseAcceptance(responsibleIndividual, cmd.timestamp, submission.id, submission.latestInstance.index)
      applicationRepository.addApplicationTermsOfUseAcceptance(appWithoutTouAcceptance.id, acceptance)
    } else {
      Future.successful(appWithoutTouAcceptance)
    }
  }

  private def createTouUpliftVerificationRecordIfNeeded(
      isRequesterTheResponsibleIndividual: Boolean,
      application: StoredApplication,
      submission: Submission,
      cmd: SubmitApplicationApprovalRequest
    ): Future[Option[ResponsibleIndividualVerificationId]] = {
    if (!isRequesterTheResponsibleIndividual) {
      for {
        verification <- responsibleIndividualVerificationService.createNewTouUpliftVerification(
                          application,
                          submission.id,
                          submission.latestInstance.index,
                          cmd.requesterName,
                          cmd.requesterEmail
                        )
      } yield Some(verification.id)

    } else {
      Future.successful(None)
    }
  }

}
