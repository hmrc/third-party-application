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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, ValidatedApplicationName}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.Status._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionDataExtracter
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.models.{DuplicateName, HasSucceeded, InvalidName, ValidName}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, AuditHelper, AuditService}

object RequestApprovalsService {
  sealed trait RequestApprovalResult

  case class ApprovalAccepted(application: StoredApplication) extends RequestApprovalResult

  sealed trait ApprovalRejectedResult                                                extends RequestApprovalResult
  case object ApprovalRejectedDueToIncorrectApplicationState                         extends ApprovalRejectedResult
  case class ApprovalRejectedDueToIncorrectSubmissionState(state: Submission.Status) extends ApprovalRejectedResult

  sealed trait ApprovalRejectedDueToName                      extends ApprovalRejectedResult
  case class ApprovalRejectedDueToDuplicateName(name: String) extends ApprovalRejectedDueToName
  case class ApprovalRejectedDueToIllegalName(name: String)   extends ApprovalRejectedDueToName
}

@Singleton
class RequestApprovalsService @Inject() (
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    approvalsNamingService: ApprovalsNamingService,
    submissionService: SubmissionsService,
    emailConnector: EmailConnector,
    responsibleIndividualVerificationService: ResponsibleIndividualVerificationService,
    applicationService: ApplicationService,
    val clock: Clock
  )(implicit ec: ExecutionContext
  ) extends BaseService(stateHistoryRepository, clock)
    with ApplicationLogger
    with ClockNow {

  import RequestApprovalsService._

  @deprecated
  def requestApproval(
      app: StoredApplication,
      submission: Submission,
      requestedByName: String,
      requestedByEmailAddress: String
    )(implicit hc: HeaderCarrier
    ): Future[RequestApprovalResult] = {

    if (app.isInProduction) {
      requestToUUpliftApproval(app, submission, requestedByName, requestedByEmailAddress)
    } else {
      requestProductionCredentialApproval(app, submission, requestedByName, requestedByEmailAddress)
    }
  }

  private def requestProductionCredentialApproval(
      originalApp: StoredApplication,
      submission: Submission,
      requestedByName: String,
      requestedByEmailAddress: String
    )(implicit hc: HeaderCarrier
    ): Future[RequestApprovalResult] = {

    def deriveNewAppDetails(
        existing: StoredApplication,
        isRequesterTheResponsibleIndividual: Boolean,
        applicationName: String,
        requestedByEmailAddress: String,
        requestedByName: String,
        importantSubmissionData: ImportantSubmissionData
      ): StoredApplication =
      existing.copy(
        name = applicationName,
        normalisedName = applicationName.toLowerCase,
        access = updateStandardData(existing.access, importantSubmissionData),
        state = if (isRequesterTheResponsibleIndividual) {
          existing.state.toPendingGatekeeperApproval(requestedByEmailAddress, requestedByName, instant())
        } else {
          existing.state.toPendingResponsibleIndividualVerification(requestedByEmailAddress, requestedByName, instant())
        }
      )

    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[ApprovalRejectedResult]

    import SubmissionDataExtracter._

    logStartingApprovalRequestProcessing(originalApp.id)
    (
      for {
        _                                  <- ET.cond(originalApp.isInTesting, (), ApprovalRejectedDueToIncorrectApplicationState)
        _                                  <- ET.cond(submission.status.isAnsweredCompletely, (), ApprovalRejectedDueToIncorrectSubmissionState(submission.status))
        appName                             = getApplicationName(submission).get                                                   // Safe at this point
        _                                  <- ET.fromEitherF(validateApplicationName(appName, originalApp.id, originalApp.access.accessType))
        isRequesterTheResponsibleIndividual = SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission)
        importantSubmissionData             = getImportantSubmissionData(submission, requestedByName, requestedByEmailAddress).get // Safe at this point
        updatedApp                          = deriveNewAppDetails(originalApp, isRequesterTheResponsibleIndividual, appName, requestedByEmailAddress, requestedByName, importantSubmissionData)
        savedApp                           <- ET.liftF(applicationRepository.save(updatedApp))
        _                                  <- ET.liftF(addTouAcceptanceIfNeeded(isRequesterTheResponsibleIndividual, updatedApp, submission, requestedByName, requestedByEmailAddress))
        _                                  <- ET.liftF(writeStateHistory(updatedApp, requestedByEmailAddress))
        updatedSubmission                   = Submission.submit(Instant.now(clock), requestedByEmailAddress)(submission)
        savedSubmission                    <- ET.liftF(submissionService.store(updatedSubmission))
        _                                  <- ET.liftF(sendResponsibleIndividualVerificationEmailIfNeeded(isRequesterTheResponsibleIndividual, savedApp, submission, importantSubmissionData, requestedByName))
        _                                   = logCompletedApprovalRequest(savedApp)
        _                                  <- ET.liftF(auditCompletedApprovalRequest(originalApp.id, savedApp))
      } yield ApprovalAccepted(savedApp)
    )
      .fold[RequestApprovalResult](identity, identity)
  }

  private def requestToUUpliftApproval(
      originalApp: StoredApplication,
      submission: Submission,
      requestedByName: String,
      requestedByEmailAddress: String
    )(implicit hc: HeaderCarrier
    ): Future[RequestApprovalResult] = {

    def deriveNewAppDetails(
        existing: StoredApplication,
        importantSubmissionData: ImportantSubmissionData
      ): StoredApplication =
      existing.copy(
        access = updateStandardData(existing.access, importantSubmissionData)
      )

    def deriveNewSubmissionsDetails(
        isRequesterTheResponsibleIndividual: Boolean,
        existingSubmission: Submission
      ): Submission = {

      if (isRequesterTheResponsibleIndividual) {
        Submission.automaticallyMark(Instant.now(clock), requestedByEmailAddress)(existingSubmission)
      } else {
        Submission.pendingResponsibleIndividual(Instant.now(clock), requestedByEmailAddress)(existingSubmission)
      }
    }

    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[ApprovalRejectedResult]

    import SubmissionDataExtracter._

    logStartingApprovalRequestProcessing(originalApp.id)
    (
      for {
        _                                  <- ET.cond(originalApp.isInProduction, (), ApprovalRejectedDueToIncorrectApplicationState)
        touInvite                          <- ET.fromOptionF(termsOfUseInvitationRepository.fetch(originalApp.id), ApprovalRejectedDueToIncorrectApplicationState)
        _                                  <- ET.cond(submission.status.isAnsweredCompletely, (), ApprovalRejectedDueToIncorrectSubmissionState(submission.status))
        isRequesterTheResponsibleIndividual = SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission)
        importantSubmissionData             = getImportantSubmissionData(submission, requestedByName, requestedByEmailAddress).get // Safe at this point
        updatedApp                          = deriveNewAppDetails(originalApp, importantSubmissionData)
        savedApp                           <- ET.liftF(applicationRepository.save(updatedApp))
        submittedSubmission                 = Submission.submit(Instant.now(clock), requestedByEmailAddress)(submission)
        updatedSubmission                   = deriveNewSubmissionsDetails(isRequesterTheResponsibleIndividual, submittedSubmission)
        savedSubmission                    <- ET.liftF(submissionService.store(updatedSubmission))
        addTouAcceptance                    = isRequesterTheResponsibleIndividual && savedSubmission.status.isGranted
        _                                  <- ET.liftF(addTouAcceptanceIfNeeded(addTouAcceptance, updatedApp, submission, requestedByName, requestedByEmailAddress))
        _                                  <- ET.liftF(setTermsOfUseInvitationStatus(savedApp.id, savedSubmission))
        _                                  <- ET.liftF(sendTouUpliftVerificationEmailIfNeeded(
                                                isRequesterTheResponsibleIndividual,
                                                savedApp,
                                                submission,
                                                importantSubmissionData,
                                                requestedByName,
                                                requestedByEmailAddress
                                              ))
        _                                  <- ET.liftF(sendConfirmationEmailIfNeeded(addTouAcceptance, savedApp))
        _                                   = logCompletedApprovalRequest(savedApp)
        _                                  <- ET.liftF(auditCompletedApprovalRequest(originalApp.id, savedApp))
      } yield ApprovalAccepted(savedApp)
    )
      .fold[RequestApprovalResult](identity, identity)
  }

  private def logStartingApprovalRequestProcessing(applicationId: ApplicationId) = {
    logger.info(s"Approval-01: approval request made for appId:${applicationId}")
  }

  private def setTermsOfUseInvitationStatus(applicationId: ApplicationId, submission: Submission) = {
    submission.status match {
      case Granted(_, _, _, _) => termsOfUseInvitationRepository.updateState(applicationId, TERMS_OF_USE_V2)
      case Warnings(_, _)      => termsOfUseInvitationRepository.updateState(applicationId, WARNINGS)
      case Failed(_, _)        => termsOfUseInvitationRepository.updateState(applicationId, FAILED)
      case _                   => successful(HasSucceeded)
    }
  }

  private def addTouAcceptanceIfNeeded(
      addTouAcceptance: Boolean,
      appWithoutTouAcceptance: StoredApplication,
      submission: Submission,
      requestedByName: String,
      requestedByEmailAddress: String
    ): Future[StoredApplication] = {
    if (addTouAcceptance) {
      val responsibleIndividual = ResponsibleIndividual.build(requestedByName, requestedByEmailAddress)
      val acceptance            = TermsOfUseAcceptance(responsibleIndividual, Instant.now(clock), submission.id, submission.latestInstance.index)
      applicationService.addTermsOfUseAcceptance(appWithoutTouAcceptance.id, acceptance)
    } else {
      Future.successful(appWithoutTouAcceptance)
    }
  }

  private def sendResponsibleIndividualVerificationEmailIfNeeded(
      isRequesterTheResponsibleIndividual: Boolean,
      application: StoredApplication,
      submission: Submission,
      importantSubmissionData: ImportantSubmissionData,
      requestedByName: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    if (!isRequesterTheResponsibleIndividual) {
      val responsibleIndividualName  = importantSubmissionData.responsibleIndividual.fullName.value
      val responsibleIndividualEmail = importantSubmissionData.responsibleIndividual.emailAddress

      for {
        verification <- responsibleIndividualVerificationService.createNewToUVerification(application, submission.id, submission.latestInstance.index)
        _            <-
          emailConnector.sendVerifyResponsibleIndividualNotification(responsibleIndividualName, responsibleIndividualEmail, application.name, requestedByName, verification.id.value)
      } yield HasSucceeded

    } else {
      Future.successful(HasSucceeded)
    }
  }

  private def sendTouUpliftVerificationEmailIfNeeded(
      isRequesterTheResponsibleIndividual: Boolean,
      application: StoredApplication,
      submission: Submission,
      importantSubmissionData: ImportantSubmissionData,
      requestedByName: String,
      requestedByEmailAddress: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    if (!isRequesterTheResponsibleIndividual) {
      val responsibleIndividualName  = importantSubmissionData.responsibleIndividual.fullName.value
      val responsibleIndividualEmail = importantSubmissionData.responsibleIndividual.emailAddress

      for {
        verification <- responsibleIndividualVerificationService.createNewTouUpliftVerification(
                          application,
                          submission.id,
                          submission.latestInstance.index,
                          requestedByName,
                          requestedByEmailAddress.toLaxEmail
                        )
        _            <-
          emailConnector.sendVerifyResponsibleIndividualUpdateNotification(
            responsibleIndividualName,
            responsibleIndividualEmail,
            application.name,
            requestedByName,
            verification.id.value
          )
      } yield HasSucceeded

    } else {
      Future.successful(HasSucceeded)
    }
  }

  private def sendConfirmationEmailIfNeeded(
      addTouAcceptance: Boolean,
      application: StoredApplication
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    if (addTouAcceptance) {
      emailConnector.sendNewTermsOfUseConfirmation(application.name, application.admins.map(_.emailAddress))
    } else {
      Future.successful(HasSucceeded)
    }
  }

  private def updateStandardData(existingAccess: Access, importantSubmissionData: ImportantSubmissionData): Access = {
    existingAccess match {
      case s: Access.Standard => s.copy(importantSubmissionData = Some(importantSubmissionData))
      case _                  => existingAccess
    }
  }

  private def validateApplicationName(appName: String, appId: ApplicationId, accessType: AccessType)(implicit hc: HeaderCarrier)
      : Future[Either[ApprovalRejectedDueToName, Unit]] = {
    ValidatedApplicationName(appName) match {
      case Some(validatedAppName) => callValidateApplicationName(validatedAppName, appId, accessType)
      case _                      => Future.successful(Left(ApprovalRejectedDueToIllegalName(appName)))
    }
  }

  private def callValidateApplicationName(appName: ValidatedApplicationName, appId: ApplicationId, accessType: AccessType)(implicit hc: HeaderCarrier)
      : Future[Either[ApprovalRejectedDueToName, Unit]] = {
    approvalsNamingService.validateApplicationNameAndAudit(appName, appId, accessType).map(_ match {
      case ValidName     => Right(ApprovalAccepted)
      case InvalidName   => Left(ApprovalRejectedDueToIllegalName(appName.toString()))
      case DuplicateName => Left(ApprovalRejectedDueToDuplicateName(appName.toString()))
    })
  }

  private def logCompletedApprovalRequest(app: StoredApplication) =
    logger.info(s"Approval-02: approval request (pending) application:${app.name} appId:${app.id} appState:${app.state.name}")

  private def auditCompletedApprovalRequest(applicationId: ApplicationId, updatedApp: StoredApplication)(implicit hc: HeaderCarrier): Future[AuditResult] =
    auditService.audit(ApplicationUpliftRequested, AuditHelper.applicationId(applicationId) ++ Map("newApplicationName" -> updatedApp.name))

  private def writeStateHistory(snapshotApp: StoredApplication, requestedByEmailAddress: String) =
    insertStateHistory(
      snapshotApp,
      snapshotApp.state.name,
      Some(State.TESTING),
      Actors.AppCollaborator(requestedByEmailAddress.toLaxEmail),
      (a: StoredApplication) => applicationRepository.save(a)
    )
}
