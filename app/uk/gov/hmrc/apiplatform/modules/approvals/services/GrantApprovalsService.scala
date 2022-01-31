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
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{AuditHelper, AuditService}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.ExtendedSubmission
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import scala.concurrent.Future.successful
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import org.joda.time.DateTime
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionStatusChanges
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.QuestionsAndAnswersToMap

object GrantApprovalsService {
  sealed trait Result

  case class Actioned(application: ApplicationData) extends Result

  sealed trait Rejected extends Result
  case object RejectedDueToIncorrectApplicationState extends Rejected
  case object RejectedDueToNoSuchApplication extends Rejected
  case object RejectedDueToNoSuchSubmission extends Rejected
  case object RejectedDueToIncompleteSubmission extends Rejected
  case object RejectedDueToIncorrectSubmissionState extends Rejected
}

@Singleton
class GrantApprovalsService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  stateHistoryRepository: StateHistoryRepository,
  submissionService: SubmissionsService
)(implicit ec: ExecutionContext)
  extends ApplicationLogger {

  import GrantApprovalsService._

  def grant(originalApp: ApplicationData, extSubmission: ExtendedSubmission, name: String)(implicit hc: HeaderCarrier): Future[GrantApprovalsService.Result] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    def logStart(applicationId: ApplicationId): Future[Unit] = {
      logger.info(s"Granted-01: grant appId:${applicationId}")
      successful(Unit)
    }

    def logDone(app: ApplicationData, submission: Submission) = 
      logger.info(s"Granted-02: grant appId:${app.id} ${app.state.name} ${submission.status}")

    val ET = EitherTHelper.make[Result]

    (
      for {
        _                     <- ET.liftF(logStart(originalApp.id))
        _                     <- ET.cond(originalApp.state.name == State.PENDING_GATEKEEPER_APPROVAL, (), RejectedDueToIncorrectApplicationState)
        _                     <- ET.cond(extSubmission.isCompleted, (), RejectedDueToIncompleteSubmission)
        _                     <- ET.cond(extSubmission.status.isSubmitted, (), RejectedDueToIncorrectSubmissionState)

        // Set application state to user verification
        updatedApp            = grantApp(originalApp)
        savedApp              <- ET.liftF(applicationRepository.save(updatedApp))
        _                     <- ET.liftF(writeStateHistory(originalApp, name))
        updatedSubmission     = updateSubmissionToGrantedState(extSubmission.submission, DateTimeUtils.now, name)
        savedSubmission       <- ET.liftF(submissionService.store(updatedSubmission))
        _                     = logDone(savedApp, savedSubmission)
        _                     <- ET.liftF(auditGrantedApprovalRequest(originalApp.id, savedApp, updatedSubmission))
      } yield Actioned(savedApp)
    )
    .fold[Result](identity, identity)
  }

  private def grantApp(application: ApplicationData): ApplicationData = {
    application.copy(state = application.state.toPendingRequesterVerification)
  }

  private def auditGrantedApprovalRequest(applicationId: ApplicationId, updatedApp: ApplicationData, submission: Submission)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    val questionsWithAnswers = QuestionsAndAnswersToMap(submission)
    
    val grantedData = Map("status" -> "granted")

    auditService.audit(ApplicationApprovalGranted, AuditHelper.applicationId(applicationId) ++ questionsWithAnswers ++ grantedData)
  }

  private def writeStateHistory(snapshotApp: ApplicationData, name: String) = 
    insertStateHistory(snapshotApp, PENDING_REQUESTER_VERIFICATION, Some(PENDING_GATEKEEPER_APPROVAL), name, GATEKEEPER, (a: ApplicationData) => applicationRepository.save(a))

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType, rollback: ApplicationData => Any): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState)
    stateHistoryRepository.insert(stateHistory)
    .andThen {
      case e: Failure[_] =>
        rollback(snapshotApp)
    }
  }
  
  private def updateSubmissionToGrantedState(submission: Submission, timestamp: DateTime, name: String): Submission = {
    SubmissionStatusChanges.grant(timestamp, name)(submission)
  }
}
