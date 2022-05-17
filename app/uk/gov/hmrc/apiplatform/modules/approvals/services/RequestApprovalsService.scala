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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, ResponsibleIndividual, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, AuditHelper, AuditService}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionDataExtracter
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.models.{DuplicateName, HasSucceeded, InvalidName, ValidName}

import scala.concurrent.Future.successful
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector

import java.time.{Clock, LocalDateTime}

object RequestApprovalsService {
  sealed trait RequestApprovalResult

  case class ApprovalAccepted(application: ApplicationData) extends RequestApprovalResult

  sealed trait ApprovalRejectedResult extends RequestApprovalResult
  case object ApprovalRejectedDueToIncorrectApplicationState extends ApprovalRejectedResult
  case class ApprovalRejectedDueToIncorrectSubmissionState(state: Submission.Status) extends ApprovalRejectedResult

  sealed trait ApprovalRejectedDueToName extends ApprovalRejectedResult
  case class ApprovalRejectedDueToDuplicateName(name: String) extends ApprovalRejectedDueToName
  case class ApprovalRejectedDueToIllegalName(name: String) extends ApprovalRejectedDueToName
}

@Singleton
class RequestApprovalsService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  stateHistoryRepository: StateHistoryRepository,
  approvalsNamingService: ApprovalsNamingService,
  submissionService: SubmissionsService,
  emailConnector: EmailConnector,
  responsibleIndividualVerificationService: ResponsibleIndividualVerificationService,
  applicationService: ApplicationService,
  clock: Clock
)(implicit ec: ExecutionContext)
  extends BaseService(stateHistoryRepository, clock) 
  with ApplicationLogger {

  import RequestApprovalsService._

  def requestApproval(originalApp: ApplicationData, submission: Submission, requestedByName: String, requestedByEmailAddress: String)(implicit hc: HeaderCarrier): Future[RequestApprovalResult] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[ApprovalRejectedResult]

    import SubmissionDataExtracter._

    (
      for {
        _                                   <- ET.liftF(logStartingApprovalRequestProcessing(originalApp.id))
        _                                   <- ET.cond(originalApp.isInTesting, (), ApprovalRejectedDueToIncorrectApplicationState)
        _                                   <- ET.cond(submission.status.isAnsweredCompletely, (), ApprovalRejectedDueToIncorrectSubmissionState(submission.status))
        appName                              = getApplicationName(submission).get // Safe at this point
        _                                   <- ET.fromEitherF(validateApplicationName(appName, originalApp.id, originalApp.access.accessType))
        isRequesterTheResponsibleIndividual  = SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission)
        importantSubmissionData              = getImportantSubmissionData(submission, requestedByName, requestedByEmailAddress).get // Safe at this point
        updatedApp                           = deriveNewAppDetails(originalApp, isRequesterTheResponsibleIndividual, appName, requestedByEmailAddress, requestedByName, importantSubmissionData)
        savedApp                            <- ET.liftF(applicationRepository.save(updatedApp))
        _                                   <- ET.liftF(addTouAcceptanceIfNeeded(isRequesterTheResponsibleIndividual, updatedApp, submission, requestedByName, requestedByEmailAddress))
        _                                   <- ET.liftF(writeStateHistory(updatedApp, requestedByEmailAddress))
        updatedSubmission                    = Submission.submit(LocalDateTime.now(clock), requestedByEmailAddress)(submission)
        savedSubmission                     <- ET.liftF(submissionService.store(updatedSubmission))
        _                                   <- ET.liftF(sendVerificationEmailIfNeeded(isRequesterTheResponsibleIndividual, savedApp, submission, importantSubmissionData, requestedByName))
        _                                    = logCompletedApprovalRequest(savedApp)
        _                                   <- ET.liftF(auditCompletedApprovalRequest(originalApp.id, savedApp))
      } yield ApprovalAccepted(savedApp)
    )
    .fold[RequestApprovalResult](identity, identity)
  }

  private def logStartingApprovalRequestProcessing(applicationId: ApplicationId): Future[Unit] = {
    logger.info(s"Approval-01: approval request made for appId:${applicationId}")
    successful(Unit)
  }

  private def addTouAcceptanceIfNeeded(isRequesterTheResponsibleIndividual: Boolean, appWithoutTouAcceptance: ApplicationData, submission: Submission,
                                       requestedByName: String, requestedByEmailAddress: String): Future[ApplicationData] = {
    if (isRequesterTheResponsibleIndividual) {
      val responsibleIndividual = ResponsibleIndividual.build(requestedByName, requestedByEmailAddress)
      val acceptance = TermsOfUseAcceptance(responsibleIndividual, LocalDateTime.now(), submission.id)
      applicationService.addTermsOfUseAcceptance(appWithoutTouAcceptance.id, acceptance)
    } else {
      Future.successful(appWithoutTouAcceptance)
    }
  }

  private def sendVerificationEmailIfNeeded(isRequesterTheResponsibleIndividual: Boolean, application: ApplicationData, 
                                            submission: Submission, importantSubmissionData: ImportantSubmissionData,
                                            requestedByName: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    if (!isRequesterTheResponsibleIndividual) {
      val responsibleIndividualName = importantSubmissionData.responsibleIndividual.fullName.value
      val responsibleIndividualEmail = importantSubmissionData.responsibleIndividual.emailAddress.value

      for {
        verification <- responsibleIndividualVerificationService.createNewVerification(application, submission.id, submission.latestInstance.index)
        _            <- emailConnector.sendVerifyResponsibleIndividualNotification(responsibleIndividualName, responsibleIndividualEmail, application.name, requestedByName, verification.id.value)
      } yield HasSucceeded

    } else {
      Future.successful(HasSucceeded)
    }
  }

  private def updateStandardData(existingAccess: Access, importantSubmissionData: ImportantSubmissionData): Access = {
    existingAccess match {
      case s : Standard => s.copy(importantSubmissionData = Some(importantSubmissionData))
      case _ => existingAccess
    }
  }
 
  private def deriveNewAppDetails(
      existing: ApplicationData,
      isRequesterTheResponsibleIndividual: Boolean,
      applicationName: String,
      requestedByEmailAddress: String,
      requestedByName: String,
      importantSubmissionData: ImportantSubmissionData
  ): ApplicationData =
    existing.copy(
      name = applicationName,
      normalisedName = applicationName.toLowerCase,
      access = updateStandardData(existing.access, importantSubmissionData),
      state = if (isRequesterTheResponsibleIndividual) 
                existing.state.toPendingGatekeeperApproval(requestedByEmailAddress, clock) 
              else 
                existing.state.toPendingResponsibleIndividualVerification(requestedByEmailAddress, requestedByName, clock)
    )

  private def validateApplicationName(appName: String, appId: ApplicationId, accessType: AccessType)(implicit hc: HeaderCarrier): Future[Either[ApprovalRejectedDueToName, Unit]] = 
    approvalsNamingService.validateApplicationNameAndAudit(appName, appId, accessType).map(_ match {
      case ValidName     => Right(ApprovalAccepted)
      case InvalidName   => Left(ApprovalRejectedDueToIllegalName(appName))
      case DuplicateName => Left(ApprovalRejectedDueToDuplicateName(appName))
    })
    
  private def logCompletedApprovalRequest(app: ApplicationData) = 
    logger.info(s"Approval-02: approval request (pending) application:${app.name} appId:${app.id} appState:${app.state.name}")

  private def auditCompletedApprovalRequest(applicationId: ApplicationId, updatedApp: ApplicationData)(implicit hc: HeaderCarrier): Future[AuditResult] = 
    auditService.audit(ApplicationUpliftRequested, AuditHelper.applicationId(applicationId) ++ Map("newApplicationName" -> updatedApp.name))

  private def writeStateHistory(snapshotApp: ApplicationData, requestedByEmailAddress: String) = 
    insertStateHistory(snapshotApp, snapshotApp.state.name, Some(TESTING), requestedByEmailAddress, COLLABORATOR, (a: ApplicationData) => applicationRepository.save(a))
}
