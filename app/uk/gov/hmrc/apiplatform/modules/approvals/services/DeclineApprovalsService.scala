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
import uk.gov.hmrc.thirdpartyapplication.services.AuditService
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import scala.concurrent.Future.successful
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.QuestionsAndAnswersToMap

object DeclineApprovalsService {
  sealed trait Result

  case class Actioned(application: ApplicationData) extends Result

  sealed trait Rejected extends Result
  case object RejectedDueToIncorrectApplicationState extends Rejected
  case object RejectedDueToIncorrectSubmissionState extends Rejected
}

@Singleton
class DeclineApprovalsService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  stateHistoryRepository: StateHistoryRepository,
  submissionService: SubmissionsService
)(implicit ec: ExecutionContext)
  extends ApplicationLogger {

  import DeclineApprovalsService._

  def decline(originalApp: ApplicationData, submission: Submission, gatekeeperUserName: String, reasons: String)(implicit hc: HeaderCarrier): Future[DeclineApprovalsService.Result] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    def logStart(applicationId: ApplicationId): Future[Unit] = {
      logger.info(s"Decline-01: decline appId:${applicationId}")
      successful(Unit)
    }

    def logDone(app: ApplicationData, submission: Submission) = 
      logger.info(s"Decline-02: decline appId:${app.id} ${app.state.name} ${submission.status}")

    val ET = EitherTHelper.make[Result]
    val appId = originalApp.id
    (
      for {
        _                     <- ET.liftF(logStart(appId))
        _                     <- ET.cond(originalApp.state.name == State.PENDING_GATEKEEPER_APPROVAL, (), RejectedDueToIncorrectApplicationState)
        _                     <- ET.cond(submission.status.isSubmitted, (), RejectedDueToIncorrectSubmissionState)

        // Set application state to user verification
        updatedApp            = declineApp(originalApp)
        savedApp              <- ET.liftF(applicationRepository.save(updatedApp))
        _                     <- ET.liftF(writeStateHistory(originalApp, gatekeeperUserName))
        updatedSubmission     = Submission.decline(DateTimeUtils.now, gatekeeperUserName, reasons)(submission)
        savedSubmission       <- ET.liftF(submissionService.store(updatedSubmission))
        _                     <- ET.liftF(auditDeclinedApprovalRequest(appId, savedApp, updatedSubmission, gatekeeperUserName, reasons))
        _                     = logDone(savedApp, savedSubmission)
      } yield Actioned(savedApp)
    )
    .fold[Result](identity, identity)
  }

  private def declineApp(application: ApplicationData): ApplicationData = {
    application.copy(state = application.state.toTesting)
  }

  private def auditDeclinedApprovalRequest(applicationId: ApplicationId, updatedApp: ApplicationData, submission: Submission, gatekeeperUserName: String, reasons: String)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    val questionsWithAnswers = QuestionsAndAnswersToMap(submission)
    
    val declinedData = Map("status" -> "declined", "reasons" -> reasons)

    val extraDetails = questionsWithAnswers ++ declinedData

    auditService.auditGatekeeperAction(gatekeeperUserName, updatedApp, ApplicationApprovalDeclined, extraDetails)
  }

  private def writeStateHistory(snapshotApp: ApplicationData, name: String) = 
    insertStateHistory(snapshotApp, TESTING, Some(PENDING_GATEKEEPER_APPROVAL), name, GATEKEEPER, (a: ApplicationData) => applicationRepository.save(a))

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
