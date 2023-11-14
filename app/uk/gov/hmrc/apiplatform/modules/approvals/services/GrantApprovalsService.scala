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

import java.time.format.DateTimeFormatter
import java.time.{Clock, LocalDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.Status._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{Fail, Submission, Warn}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.{MarkAnswer, QuestionsAndAnswersToMap}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ImportantSubmissionData, Standard, TermsOfUseAcceptance}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{AuditService, TermsOfUseInvitationService}

object GrantApprovalsService {
  sealed trait Result

  case class Actioned(application: ApplicationData) extends Result

  sealed trait Rejected                              extends Result
  case object RejectedDueToIncorrectApplicationState extends Rejected
  case object RejectedDueToIncorrectSubmissionState  extends Rejected
  case object RejectedDueToIncorrectApplicationData  extends Rejected
}

@Singleton
class GrantApprovalsService @Inject() (
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    termsOfUseInvitationService: TermsOfUseInvitationService,
    submissionService: SubmissionsService,
    emailConnector: EmailConnector,
    clock: Clock
  )(implicit ec: ExecutionContext
  ) extends BaseService(stateHistoryRepository, clock)
    with ApplicationLogger {

  import GrantApprovalsService._

  def grant(
      originalApp: ApplicationData,
      submission: Submission,
      gatekeeperUserName: String,
      warnings: Option[String],
      escalatedTo: Option[String]
    )(implicit hc: HeaderCarrier
    ): Future[GrantApprovalsService.Result] = {
    import cats.instances.future.catsStdInstancesForFuture

    def logDone(app: ApplicationData, submission: Submission) =
      logger.info(s"Granted-02: grant appId:${app.id} ${app.state.name} ${submission.status.getClass.getSimpleName}")

    val ET    = EitherTHelper.make[Result]
    val appId = originalApp.id

    logger.info(s"Granted-01: grant appId:${appId}")
    (
      for {
        _ <- ET.cond(originalApp.isPendingGatekeeperApproval, (), RejectedDueToIncorrectApplicationState)
        _ <- ET.cond(submission.status.isSubmitted, (), RejectedDueToIncorrectSubmissionState)

        // Set application state to user verification
        updatedApp               = grantApp(originalApp)
        savedApp                <- ET.liftF(applicationRepository.save(updatedApp))
        importantSubmissionData <- ET.fromOption(savedApp.importantSubmissionData, RejectedDueToIncorrectApplicationData)
        _                       <- ET.liftF(writeStateHistory(originalApp, gatekeeperUserName))
        updatedSubmission        = grantSubmission(gatekeeperUserName, warnings, escalatedTo)(submission)
        savedSubmission         <- ET.liftF(submissionService.store(updatedSubmission))
        _                       <- ET.liftF(auditGrantedApprovalRequest(appId, savedApp, savedSubmission, gatekeeperUserName, warnings, importantSubmissionData, escalatedTo))
        _                       <- ET.liftF(sendEmails(savedApp))
        _                        = logDone(savedApp, savedSubmission)
      } yield Actioned(savedApp)
    )
      .fold[Result](identity, identity)
  }

  private def grantSubmission(gatekeeperUserName: String, warnings: Option[String], escalatedTo: Option[String])(submission: Submission) = {
    warnings.fold(
      Submission.grant(LocalDateTime.now(clock), gatekeeperUserName, None, None)(submission)
    )(value =>
      Submission.grantWithWarnings(LocalDateTime.now(clock), gatekeeperUserName, value, escalatedTo)(submission)
    )
  }

  private def grantApp(application: ApplicationData): ApplicationData = {
    application.copy(state = application.state.toPendingRequesterVerification(clock))
  }

  private val fmt = DateTimeFormatter.ISO_DATE_TIME

  private def auditGrantedApprovalRequest(
      applicationId: ApplicationId,
      updatedApp: ApplicationData,
      submission: Submission,
      gatekeeperUserName: String,
      warnings: Option[String],
      importantSubmissionData: ImportantSubmissionData,
      escalatedTo: Option[String]
    )(implicit hc: HeaderCarrier
    ): Future[AuditResult] = {

    val questionsWithAnswers                                         = QuestionsAndAnswersToMap(submission)
    val grantedData                                                  = Map("status" -> "granted")
    val warningsData                                                 = warnings.fold(Map.empty[String, String])(warning => Map("warnings" -> warning))
    val escalatedData                                                = escalatedTo.fold(Map.empty[String, String])(escalatedTo => Map("escalatedTo" -> escalatedTo))
    val submittedOn: LocalDateTime                                   = submission.latestInstance.statusHistory.find(s => s.isSubmitted).map(_.timestamp).get
    val grantedOn: LocalDateTime                                     = submission.latestInstance.statusHistory.find(s => s.isGrantedWithOrWithoutWarnings).map(_.timestamp).get
    val responsibleIndividualVerificationDate: Option[LocalDateTime] =
      importantSubmissionData.termsOfUseAcceptances.find(t => (t.submissionId == submission.id && t.submissionInstance == submission.latestInstance.index)).map(_.dateTime)
    val dates                                                        = Map(
      "submission.started.date"   -> submission.startedOn.format(fmt),
      "submission.submitted.date" -> submittedOn.format(fmt),
      "submission.granted.date"   -> grantedOn.format(fmt)
    ) ++ responsibleIndividualVerificationDate.fold(Map.empty[String, String])(rivd => Map("responsibleIndividual.verification.date" -> rivd.format(fmt)))

    val markedAnswers = MarkAnswer.markSubmission(submission)
    val nbrOfFails    = markedAnswers.filter(_._2 == Fail).size
    val nbrOfWarnings = markedAnswers.filter(_._2 == Warn).size
    val counters      = Map(
      "submission.failures" -> nbrOfFails.toString,
      "submission.warnings" -> nbrOfWarnings.toString
    )

    val extraData = questionsWithAnswers ++ grantedData ++ warningsData ++ dates ++ counters ++ escalatedData

    auditService.auditGatekeeperAction(gatekeeperUserName, updatedApp, ApplicationApprovalGranted, extraData)
  }

  private def writeStateHistory(snapshotApp: ApplicationData, name: String) =
    insertStateHistory(
      snapshotApp,
      PENDING_REQUESTER_VERIFICATION,
      Some(PENDING_GATEKEEPER_APPROVAL),
      Actors.GatekeeperUser(name),
      (a: ApplicationData) => applicationRepository.save(a)
    )

  private def sendEmails(app: ApplicationData)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val requesterEmail   = app.state.requestedByEmailAddress.getOrElse(throw new RuntimeException("no requestedBy email found")).toLaxEmail
    val verificationCode = app.state.verificationCode.getOrElse(throw new RuntimeException("no verification code found"))
    val recipients       = app.admins.map(_.emailAddress).filterNot(email => email.equalsIgnoreCase(requesterEmail))

    if (recipients.nonEmpty) emailConnector.sendApplicationApprovedNotification(app.name, recipients)

    emailConnector.sendApplicationApprovedAdminConfirmation(app.name, verificationCode, Set(requesterEmail))
  }

  private def setTermsOfUseInvitationStatus(applicationId: ApplicationId, submission: Submission) = {
    submission.status match {
      case Granted(_, _, _, _)             => termsOfUseInvitationService.updateStatus(applicationId, TERMS_OF_USE_V2)
      case GrantedWithWarnings(_, _, _, _) => termsOfUseInvitationService.updateStatus(applicationId, TERMS_OF_USE_V2_WITH_WARNINGS)
      case Warnings(_, _)                  => termsOfUseInvitationService.updateStatus(applicationId, WARNINGS)
      case Failed(_, _)                    => termsOfUseInvitationService.updateStatus(applicationId, FAILED)
      case Answering(_, _)                 => termsOfUseInvitationService.updateResetBackToEmailSent(applicationId)
      case _                               => successful(HasSucceeded)
    }
  }

  def grantWithWarningsForTouUplift(
      originalApp: ApplicationData,
      submission: Submission,
      gatekeeperUserName: String,
      reasons: String
    ): Future[GrantApprovalsService.Result] = {
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[Result]
    (
      for {
        _ <- ET.cond(originalApp.isInProduction, (), RejectedDueToIncorrectApplicationState)
        _ <- ET.cond(submission.status.isWarnings, (), RejectedDueToIncorrectSubmissionState)

        updatedSubmission = Submission.grantWithWarnings(LocalDateTime.now(clock), gatekeeperUserName, reasons, None)(submission)
        savedSubmission  <- ET.liftF(submissionService.store(updatedSubmission))
        _                <- ET.liftF(setTermsOfUseInvitationStatus(originalApp.id, savedSubmission))
      } yield Actioned(originalApp)
    )
      .fold[Result](identity, identity)
  }

  def grantForTouUplift(
      originalApp: ApplicationData,
      submission: Submission,
      gatekeeperUserName: String,
      comments: String,
      escalatedTo: Option[String]
    )(implicit hc: HeaderCarrier
    ): Future[GrantApprovalsService.Result] = {
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[Result]
    (
      for {
        _ <- ET.cond(originalApp.isInProduction, (), RejectedDueToIncorrectApplicationState)
        _ <- ET.cond((submission.status.isGrantedWithWarnings || submission.status.isFailed), (), RejectedDueToIncorrectSubmissionState)

        updatedSubmission      = Submission.grant(LocalDateTime.now(clock), gatekeeperUserName, Some(comments), escalatedTo)(submission)
        savedSubmission       <- ET.liftF(submissionService.store(updatedSubmission))
        _                     <- ET.liftF(setTermsOfUseInvitationStatus(originalApp.id, savedSubmission))
        responsibleIndividual <- ET.fromOption(getResponsibleIndividual(originalApp), RejectedDueToIncorrectApplicationData)
        acceptance             = TermsOfUseAcceptance(responsibleIndividual, LocalDateTime.now(clock), submission.id, submission.latestInstance.index)
        _                     <- ET.liftF(applicationRepository.addApplicationTermsOfUseAcceptance(originalApp.id, acceptance))
        _                     <- ET.liftF(emailConnector.sendNewTermsOfUseConfirmation(originalApp.name, originalApp.admins.map(_.emailAddress)))
      } yield Actioned(originalApp)
    )
      .fold[Result](identity, identity)
  }

  private def getResponsibleIndividual(app: ApplicationData) =
    app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) => Some(responsibleIndividual)
      case _                                                                                            => None
    }

  def declineForTouUplift(
      originalApp: ApplicationData,
      submission: Submission,
      gatekeeperUserName: String,
      reasons: String
    ): Future[GrantApprovalsService.Result] = {
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[Result]
    (
      for {
        _ <- ET.cond(originalApp.isInProduction, (), RejectedDueToIncorrectApplicationState)
        _ <- ET.cond(submission.status.isFailed, (), RejectedDueToIncorrectSubmissionState)

        updatedSubmission = Submission.decline(LocalDateTime.now(clock), gatekeeperUserName, reasons)(submission)
        savedSubmission  <- ET.liftF(submissionService.store(updatedSubmission))
        _                <- ET.liftF(setTermsOfUseInvitationStatus(originalApp.id, savedSubmission))
      } yield Actioned(originalApp)
    )
      .fold[Result](identity, identity)
  }
}
