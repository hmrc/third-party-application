/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.approvals.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, _}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationNamingService, AuditHelper, AuditService}
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.ExtendedSubmission
import uk.gov.hmrc.thirdpartyapplication.util.EitherTHelper
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.SubmissionDataExtracter
import uk.gov.hmrc.play.audit.http.connector.AuditResult

object ApplicationApprovalsService {
  sealed trait RequestApprovalResult

  case object ApprovalAccepted extends RequestApprovalResult

  sealed trait ApprovalRejectedResult extends RequestApprovalResult
  case object ApprovalRejectedDueToIncorrectState extends ApprovalRejectedResult
  case object ApprovalRejectedDueNoSuchApplication extends ApprovalRejectedResult
  case object ApprovalRejectedDueNoSuchSubmission extends ApprovalRejectedResult
  case object ApprovalRejectedDueToIncompleteSubmission extends ApprovalRejectedResult

  sealed trait ApprovalRejectedDueToName extends ApprovalRejectedResult
  case class ApprovalRejectedDueToDuplicateName(name: String) extends ApprovalRejectedDueToName
  case class ApprovalRejectedDueToIllegalName(name: String) extends ApprovalRejectedDueToName
}
@Singleton
class ApplicationApprovalsService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  stateHistoryRepository: StateHistoryRepository,
  applicationNamingService: ApplicationNamingService,
  submissionService: SubmissionsService
)(implicit ec: ExecutionContext)
  extends ApplicationLogger {

  import ApplicationApprovalsService._

  def requestApproval(applicationId: ApplicationId,
                      requestedByEmailAddress: String)(implicit hc: HeaderCarrier): Future[RequestApprovalResult] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[ApprovalRejectedResult]

    def deriveNewAppDetails(existing: ApplicationData, applicationName: String): ApplicationData = existing.copy(
      name = applicationName,
      normalisedName = applicationName.toLowerCase,
      state = existing.state.toPendingGatekeeperApproval(requestedByEmailAddress)
    )

    (
      for {
        app            <- ET.fromOptionF(fetchApp(applicationId), ApprovalRejectedDueNoSuchApplication)
        _              <- ET.cond(app.state.name == State.TESTING, (), ApprovalRejectedDueToIncorrectState)
        extSubmission  <- ET.fromOptionF(fetchExtendedSubmission(applicationId), ApprovalRejectedDueNoSuchSubmission)
        _              <- ET.cond(extSubmission.isCompleted, (), ApprovalRejectedDueToIncompleteSubmission)
        appName         = getApplicationName(extSubmission)
        _              <- ET.fromEitherF(validateApplicationName(appName))
        updatedApp      = deriveNewAppDetails(app, appName)

        _              <- ET.liftF(writeStateHistory(app, requestedByEmailAddress))
        _               = logCompletedApprovalRequest(updatedApp)
        _              <- ET.liftF(auditCompletedApprovalRequest(applicationId, updatedApp))
      } yield ApprovalAccepted
    )
    .fold[RequestApprovalResult](identity,identity)
  }

  private def validateApplicationName(appName: String): Future[Either[ApprovalRejectedDueToName, Unit]] = ???

  private def logCompletedApprovalRequest(app: ApplicationData) = 
    logger.info(s"Approval-01: approval request (pending) application:${app.name} appId:${app.id} appState:${app.state.name} appRequestedByEmailAddress:${app.state.requestedByEmailAddress}")

  private def auditCompletedApprovalRequest(applicationId: ApplicationId, updatedApp: ApplicationData)(implicit hc: HeaderCarrier): Future[AuditResult] = 
    auditService.audit(ApplicationUpliftRequested, AuditHelper.applicationId(applicationId) ++ Map("newApplicationName" -> updatedApp.name))

  private def writeStateHistory(app: ApplicationData, requestedByEmailAddress: String) = 
    insertStateHistory(app, PENDING_GATEKEEPER_APPROVAL, Some(TESTING), requestedByEmailAddress, COLLABORATOR, (a: ApplicationData) => applicationRepository.save(a))

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType, rollback: ApplicationData => Any): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState)
    stateHistoryRepository.insert(stateHistory)
    .andThen {
      case e: Failure[_] =>
        rollback(snapshotApp)
    }
  }
  
  private def getApplicationName(extSubmission: ExtendedSubmission): String = {
    // Only calls here if we have a completed submission so this `.get` is safe
    SubmissionDataExtracter.getApplicationName(extSubmission.submission).get  
  }

  private def fetchExtendedSubmission(applicationId: ApplicationId): Future[Option[ExtendedSubmission]] = {
    submissionService.fetchLatest(applicationId)
  }

  // Pure duplicate
  private def fetchApp(applicationId: ApplicationId): Future[Option[ApplicationData]] = {
    applicationRepository.fetch(applicationId)
  }
}