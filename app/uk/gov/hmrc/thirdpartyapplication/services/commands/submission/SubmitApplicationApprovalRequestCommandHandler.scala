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
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationName, ApplicationState, State, ValidatedApplicationName}
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.ApplicationNameValidationResult
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ImportantSubmissionData
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
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class SubmitApplicationApprovalRequestCommandHandler @Inject() (
    submissionService: SubmissionsService,
    val applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    val termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    approvalsNamingService: ApprovalsNamingService,
    responsibleIndividualVerificationService: ResponsibleIndividualVerificationService
  )(implicit val ec: ExecutionContext
  ) extends SubmissionApprovalCommandsHandler with ApplicationLogger {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, (Submission, String)]] = {
    (
      for {
        submission         <- OptionT(submissionService.fetchLatest(app.id))
        nameFromSubmission <- OptionT.fromOption[Future](SubmissionDataExtracter.getApplicationName(submission))
        nameValidation     <- OptionT.liftF[Future, ApplicationNameValidationResult](validateApplicationName(nameFromSubmission, app.id))
      } yield (submission, nameFromSubmission, nameValidation)
    )
      .fold[Validated[Failures, (Submission, String)]](
        GenericFailure(s"No submission found for application ${app.id.value}").invalidNel[(Submission, String)]
      ) {
        case (submission, nameFromSubmission, nameValidationResult) => {
          Apply[Validated[Failures, *]].map5(
            ensureStandardAccess(app),
            isInTesting(app),
            cond(submission.status.isAnsweredCompletely, "Submission has not been answered completely"),
            cond(nameValidationResult != ApplicationNameValidationResult.Duplicate, CommandFailures.DuplicateApplicationName(nameFromSubmission)),
            cond(nameValidationResult != ApplicationNameValidationResult.Invalid, CommandFailures.InvalidApplicationName(nameFromSubmission))
          ) { case _ => (submission, nameFromSubmission) }
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
    val submittedEvents = NonEmptyList.of(ApplicationApprovalRequestSubmitted(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = cmd.actor,
      submissionId = submission.id,
      submissionIndex = submission.latestInstance.index,
      requestingAdminName = cmd.requesterName,
      requestingAdminEmail = cmd.requesterEmail
    ))

    if (!isRequesterTheResponsibleIndividual && verificationId.nonEmpty) {
      submittedEvents ++ List(
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
    } else submittedEvents
  }

  def process(app: StoredApplication, cmd: SubmitApplicationApprovalRequest): AppCmdResultT = {
    import SubmissionDataExtracter._

    logStartingApprovalRequestProcessing(app.id)

    for {
      validated                          <- E.fromValidatedF(validate(app))
      (submission, newAppName)            = validated
      isRequesterTheResponsibleIndividual = SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission)
      importantSubmissionData             = getImportantSubmissionData(submission, cmd.requesterName, cmd.requesterEmail.text).get // Safe at this point
      newAppState                         = determineNewApplicationState(isRequesterTheResponsibleIndividual, app, cmd)
      savedApp                           <- E.liftF(applicationRepository.save(deriveNewAppDetails(app, ApplicationName(newAppName), importantSubmissionData, newAppState)))
      updatedApp                         <- E.liftF(addTouAcceptanceIfNeeded(isRequesterTheResponsibleIndividual, savedApp, submission, cmd.timestamp, cmd.requesterName, cmd.requesterEmail))
      _                                  <- E.liftF(stateHistoryRepository.insert(createStateHistory(updatedApp, State.TESTING, Actors.AppCollaborator(cmd.requesterEmail), cmd.timestamp)))
      updatedSubmission                   = Submission.submit(cmd.timestamp, cmd.requesterEmail.text)(submission)
      savedSubmission                    <- E.liftF(submissionService.store(updatedSubmission))
      createTouUpliftResult               = createResponsibleIndividualVerificationRecordIfNeeded(isRequesterTheResponsibleIndividual, savedApp, savedSubmission, cmd)

      verificationId <- E.liftF(createTouUpliftResult)
      _               = logCompletedApprovalRequest(savedApp)
      events          = asEvents(updatedApp, cmd, savedSubmission, isRequesterTheResponsibleIndividual, verificationId, importantSubmissionData)
    } yield (updatedApp, events)
  }

  private def determineNewApplicationState(isRequesterTheResponsibleIndividual: Boolean, app: StoredApplication, cmd: SubmitApplicationApprovalRequest): ApplicationState = {
    if (isRequesterTheResponsibleIndividual) {
      app.state.toPendingGatekeeperApproval(cmd.requesterEmail.text, cmd.requesterName, cmd.timestamp)
    } else {
      app.state.toPendingResponsibleIndividualVerification(cmd.requesterEmail.text, cmd.requesterName, cmd.timestamp)
    }
  }

  private def logStartingApprovalRequestProcessing(applicationId: ApplicationId): Unit = {
    logger.info(s"Approval-01: approval request made for appId:${applicationId}")
  }

  private def logCompletedApprovalRequest(app: StoredApplication): Unit =
    logger.info(s"Approval-02: approval request (pending) application:${app.name} appId:${app.id} appState:${app.state.name}")

  private def validateApplicationName(appName: String, appId: ApplicationId): Future[ApplicationNameValidationResult] = {
    ValidatedApplicationName(appName) match {
      case Some(validatedAppName) => approvalsNamingService.validateApplicationName(validatedAppName, appId)
      case _                      => Future.successful(ApplicationNameValidationResult.Invalid)
    }
  }

  private def deriveNewAppDetails(
      existing: StoredApplication,
      applicationName: ApplicationName,
      importantSubmissionData: ImportantSubmissionData,
      newState: ApplicationState
    ): StoredApplication = existing.copy(
    name = applicationName,
    normalisedName = applicationName.value.toLowerCase,
    access = updateStandardData(existing.access, importantSubmissionData),
    state = newState
  )

  private def createResponsibleIndividualVerificationRecordIfNeeded(
      isRequesterTheResponsibleIndividual: Boolean,
      application: StoredApplication,
      submission: Submission,
      cmd: SubmitApplicationApprovalRequest
    ): Future[Option[ResponsibleIndividualVerificationId]] = {
    if (!isRequesterTheResponsibleIndividual) {
      for {
        verification <- responsibleIndividualVerificationService.createNewToUVerification(
                          application,
                          submission.id,
                          submission.latestInstance.index
                        )
      } yield Some(verification.id)

    } else {
      Future.successful(None)
    }
  }

}
