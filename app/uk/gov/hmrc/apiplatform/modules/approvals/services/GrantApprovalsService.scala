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
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.QuestionsAndAnswersToMap
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.MarkAnswer

import java.time.{Clock, LocalDateTime}

object GrantApprovalsService {
  sealed trait Result

  case class Actioned(application: ApplicationData) extends Result

  sealed trait Rejected extends Result
  case object RejectedDueToIncorrectApplicationState extends Rejected
  case object RejectedDueToIncorrectSubmissionState extends Rejected
}

@Singleton
class GrantApprovalsService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  stateHistoryRepository: StateHistoryRepository,
  submissionService: SubmissionsService,
  emailConnector: EmailConnector,
  val clock: Clock
)(implicit ec: ExecutionContext)
  extends ApplicationLogger {

  import GrantApprovalsService._

  def grant(
      originalApp: ApplicationData,
      submission: Submission, 
      gatekeeperUserName: String,
      warnings: Option[String],
      responsibleIndividualVerificationDate: Option[DateTime],
      escalatedTo: Option[String]
  )(implicit hc: HeaderCarrier): Future[GrantApprovalsService.Result] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    def logStart(applicationId: ApplicationId): Future[Unit] = {
      logger.info(s"Granted-01: grant appId:${applicationId}")
      successful(Unit)
    }

    def logDone(app: ApplicationData, submission: Submission) = 
      logger.info(s"Granted-02: grant appId:${app.id} ${app.state.name} ${submission.status}")

    val ET = EitherTHelper.make[Result]
    val appId = originalApp.id
    (
      for {
        _                     <- ET.liftF(logStart(appId))
        _                     <- ET.cond(originalApp.isPendingGatekeeperApproval, (), RejectedDueToIncorrectApplicationState)
        _                     <- ET.cond(submission.status.isSubmitted, (), RejectedDueToIncorrectSubmissionState)

        // Set application state to user verification
        updatedApp            = grantApp(originalApp)
        savedApp              <- ET.liftF(applicationRepository.save(updatedApp))
        _                     <- ET.liftF(writeStateHistory(originalApp, gatekeeperUserName))
        updatedSubmission     = grantSubmission(gatekeeperUserName, warnings, escalatedTo)(submission)
        savedSubmission       <- ET.liftF(submissionService.store(updatedSubmission))
        _                     <- ET.liftF(auditGrantedApprovalRequest(appId, savedApp, savedSubmission, gatekeeperUserName, warnings, responsibleIndividualVerificationDate, escalatedTo))
        _                     <- ET.liftF(sendEmails(savedApp))
        _                     = logDone(savedApp, savedSubmission)
      } yield Actioned(savedApp)
    )
    .fold[Result](identity, identity)
  }

  private def grantSubmission(gatekeeperUserName: String, warnings: Option[String], escalatedTo: Option[String])(submission: Submission) = {
    warnings.fold(
      Submission.grant(LocalDateTime.now(clock), gatekeeperUserName)(submission)
    )( value =>
      Submission.grantWithWarnings(LocalDateTime.now(clock), gatekeeperUserName, value, escalatedTo)(submission)
    )
  }

  private def grantApp(application: ApplicationData): ApplicationData = {
    application.copy(state = application.state.toPendingRequesterVerification(clock))
  }

  private val fmt = ISODateTimeFormat.dateTime()

  private def auditGrantedApprovalRequest(
      applicationId: ApplicationId,
      updatedApp: ApplicationData,
      submission: Submission,
      gatekeeperUserName: String,
      warnings: Option[String],
      responsibleIndividualVerificationDate: Option[DateTime],
      escalatedTo: Option[String]
  )(implicit hc: HeaderCarrier): Future[AuditResult] = {

    val questionsWithAnswers = QuestionsAndAnswersToMap(submission)
    val grantedData = Map("status" -> "granted")
    val warningsData = warnings.fold(Map.empty[String, String])(warning => Map("warnings" -> warning))
    val escalatedData = escalatedTo.fold(Map.empty[String, String])(escalatedTo => Map("escalatedTo" -> escalatedTo))
    val submittedOn: DateTime = submission.latestInstance.statusHistory.find(s => s.isSubmitted).map(_.timestamp).get
    val grantedOn: DateTime = submission.latestInstance.statusHistory.find(s => s.isGrantedWithOrWithoutWarnings).map(_.timestamp).get
    val dates = Map(
      "submission.started.date" -> submission.startedOn.toString(fmt),
      "submission.submitted.date" -> submittedOn.toString(fmt),
      "submission.granted.date" -> grantedOn.toString(fmt)
    ) ++ responsibleIndividualVerificationDate.fold(Map.empty[String,String])(rivd => Map("responsibleIndividiual.verification.date" -> rivd.toString(fmt)))
    
    val markedAnswers =  MarkAnswer.markSubmission(submission)
    val nbrOfFails = markedAnswers.filter(_._2 == Fail).size
    val nbrOfWarnings = markedAnswers.filter(_._2 == Warn).size
    val counters = Map(
      "submission.failures" -> nbrOfFails.toString,
      "submission.warnings" -> nbrOfWarnings.toString
    )

    val extraData = questionsWithAnswers ++ grantedData ++ warningsData ++ dates ++ counters ++ escalatedData

    auditService.auditGatekeeperAction(gatekeeperUserName, updatedApp, ApplicationApprovalGranted, extraData)
  }

  private def writeStateHistory(snapshotApp: ApplicationData, name: String) = 
    insertStateHistory(snapshotApp, PENDING_REQUESTER_VERIFICATION, Some(PENDING_GATEKEEPER_APPROVAL), name, GATEKEEPER, (a: ApplicationData) => applicationRepository.save(a))

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType, rollback: ApplicationData => Any): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState, changedAt = LocalDateTime.now(clock))
    stateHistoryRepository.insert(stateHistory)
    .andThen {
      case e: Failure[_] =>
        rollback(snapshotApp)
    }
  }

  private def sendEmails(app: ApplicationData)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val requesterEmail = app.state.requestedByEmailAddress.getOrElse(throw new RuntimeException("no requestedBy email found"))
    val verificationCode = app.state.verificationCode.getOrElse(throw new RuntimeException("no verification code found"))
    val recipients = app.admins.map(_.emailAddress).filterNot(email => email.equals(requesterEmail))

    if (recipients.nonEmpty) emailConnector.sendApplicationApprovedNotification(app.name, recipients)

    emailConnector.sendApplicationApprovedAdminConfirmation(app.name, verificationCode, Set(requesterEmail))
  }
}
