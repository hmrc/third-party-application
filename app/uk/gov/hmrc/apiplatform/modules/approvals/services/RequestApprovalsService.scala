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
import scala.util.Failure

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{AuditHelper, AuditService}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.ExtendedSubmission
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionDataExtracter
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.models.{ValidName, InvalidName, DuplicateName}
import scala.concurrent.Future.successful
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionStatusChanges

object RequestApprovalsService {
  sealed trait RequestApprovalResult

  case class ApprovalAccepted(application: ApplicationData) extends RequestApprovalResult

  sealed trait ApprovalRejectedResult extends RequestApprovalResult
  case object ApprovalRejectedDueToIncorrectApplicationState extends ApprovalRejectedResult
  case object ApprovalRejectedDueToIncompleteSubmission extends ApprovalRejectedResult
  case object ApprovalRejectedDueToAlreadySubmitted extends ApprovalRejectedResult

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
  submissionService: SubmissionsService
)(implicit ec: ExecutionContext)
  extends ApplicationLogger {

  import RequestApprovalsService._

  def requestApproval(originalApp: ApplicationData, extSubmission: ExtendedSubmission, requestedByEmailAddress: String)(implicit hc: HeaderCarrier): Future[RequestApprovalResult] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[ApprovalRejectedResult]

    import SubmissionDataExtracter._

    (
      for {
        _                         <- ET.liftF(logStartingApprovalRequestProcessing(originalApp.id))
        _                         <- ET.cond(originalApp.state.name == State.TESTING, (), ApprovalRejectedDueToIncorrectApplicationState)
        _                         <- ET.cond(extSubmission.isCompleted, (), ApprovalRejectedDueToIncompleteSubmission)
        _                         <- ET.cond(extSubmission.canBeSubmitted, (), ApprovalRejectedDueToAlreadySubmitted)
        submission                 = extSubmission.submission
        appName                    = getApplicationName(submission).get // Safe at this point
        _                         <- ET.fromEitherF(validateApplicationName(appName, originalApp.id, originalApp.access.accessType))
        privacyPolicyUrl           = getPrivacyPolicyUrl(submission)
        termsAndConditionsUrl      = getTermsAndConditionsUrl(submission)
        organisationUrl            = getOrganisationUrl(submission)
        responsibleIndividualName  = getResponsibleIndividualName(submission).get // Safe at this point
        responsibleIndividualEmail = getResponsibleIndividualEmail(submission).get // Safe at this point
        updatedApp                 = deriveNewAppDetails(originalApp, appName, requestedByEmailAddress, privacyPolicyUrl, termsAndConditionsUrl, organisationUrl, responsibleIndividualName, responsibleIndividualEmail)
        savedApp                  <- ET.liftF(applicationRepository.save(updatedApp))
        _                         <- ET.liftF(writeStateHistory(originalApp, requestedByEmailAddress))
        updatedSubmission          = SubmissionStatusChanges.submit(DateTimeUtils.now, requestedByEmailAddress)(submission)
        savedSubmission           <- ET.liftF(submissionService.store(updatedSubmission))
        _                          = logCompletedApprovalRequest(savedApp)
        _                         <- ET.liftF(auditCompletedApprovalRequest(originalApp.id, savedApp))
      } yield ApprovalAccepted(savedApp)
    )
    .fold[RequestApprovalResult](identity, identity)
  }

    private def logStartingApprovalRequestProcessing(applicationId: ApplicationId): Future[Unit] = {
    logger.info(s"Approval-01: approval request made for appId:${applicationId}")
    successful(Unit)
  }
  
  private def replaceUrlsInAccess(existingAccess: Access, newPrivacyPolicyUrl: Option[String], newTermsAndConditionsUrl: Option[String], newOrganisationUrl: Option[String]): Access = {
    existingAccess match {
      case s : Standard =>
        s.copy(
          termsAndConditionsUrl = newTermsAndConditionsUrl,
          privacyPolicyUrl      = newPrivacyPolicyUrl,
          organisationUrl       = newOrganisationUrl
        )
      case _ => existingAccess    
    }
  }
 
  private def deriveNewAppDetails(
      existing: ApplicationData,
      applicationName: String,
      requestedByEmailAddress: String,
      privacyPolicyUrl: Option[String],
      termsAndConditionsUrl: Option[String],
      organisationUrl: Option[String],
      responsibleIndividualName: String,
      responsibleIndividualEmail: String
  ): ApplicationData = 
    existing.copy(
      name = applicationName,
      normalisedName = applicationName.toLowerCase,
      state = existing.state.toPendingGatekeeperApproval(requestedByEmailAddress),
      access = replaceUrlsInAccess(existing.access, privacyPolicyUrl, termsAndConditionsUrl, organisationUrl),
      responsibleIndividual = Some(ResponsibleIndividual(responsibleIndividualName, responsibleIndividualEmail))
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
    insertStateHistory(snapshotApp, PENDING_GATEKEEPER_APPROVAL, Some(TESTING), requestedByEmailAddress, COLLABORATOR, (a: ApplicationData) => applicationRepository.save(a))

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType, rollback: ApplicationData => Any): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState)
    stateHistoryRepository.insert(stateHistory)
    .andThen {
      case e: Failure[_] =>
        rollback(snapshotApp)
    }
  }
}
