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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.Status._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.{AuditService, TermsOfUseInvitationService}

object GrantApprovalsService {
  sealed trait Result

  case class Actioned(application: StoredApplication) extends Result

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
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    submissionService: SubmissionsService,
    emailConnector: EmailConnector,
    val clock: Clock
  )(implicit ec: ExecutionContext
  ) extends BaseService(stateHistoryRepository, clock)
    with ApplicationLogger
    with ClockNow {

  import GrantApprovalsService._

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
      originalApp: StoredApplication,
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

        updatedSubmission = Submission.grantWithWarnings(Instant.now(clock), gatekeeperUserName, reasons, None)(submission)
        savedSubmission  <- ET.liftF(submissionService.store(updatedSubmission))
        _                <- ET.liftF(setTermsOfUseInvitationStatus(originalApp.id, savedSubmission))
      } yield Actioned(originalApp)
    )
      .fold[Result](identity, identity)
  }

  def declineForTouUplift(
      originalApp: StoredApplication,
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

        updatedSubmission = Submission.decline(Instant.now(clock), gatekeeperUserName, reasons)(submission)
        savedSubmission  <- ET.liftF(submissionService.store(updatedSubmission))
        _                <- ET.liftF(setTermsOfUseInvitationStatus(originalApp.id, savedSubmission))
      } yield Actioned(originalApp)
    )
      .fold[Result](identity, identity)
  }

  def resetForTouUplift(
      originalApp: StoredApplication,
      submission: Submission,
      gatekeeperUserName: String,
      reasons: String
    ): Future[GrantApprovalsService.Result] = {
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[Result]
    (
      for {
        _ <- ET.cond(originalApp.isInProduction, (), RejectedDueToIncorrectApplicationState)

        updatedSubmission = Submission.decline(Instant.now(clock), gatekeeperUserName, "RESET: " + reasons)(submission)
        savedSubmission  <- ET.liftF(submissionService.store(updatedSubmission))
        _                <- ET.liftF(responsibleIndividualVerificationRepository.deleteSubmissionInstance(submission.id, submission.latestInstance.index))
        _                <- ET.liftF(setTermsOfUseInvitationStatus(originalApp.id, savedSubmission))
      } yield Actioned(originalApp)
    )
      .fold[Result](identity, identity)
  }

  def deleteTouUplift(
      originalApp: StoredApplication,
      submission: Submission,
      gatekeeperUserName: String
    ): Future[GrantApprovalsService.Result] = {
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[Result]
    (
      for {
        _     <- ET.cond(originalApp.isInProduction, (), RejectedDueToIncorrectApplicationState)
        count <- ET.liftF(submissionService.deleteAllAnswersForApplication(originalApp.id))
        _     <- ET.liftF(responsibleIndividualVerificationRepository.deleteAllByApplicationId(originalApp.id))
        _     <- ET.liftF(termsOfUseInvitationService.updateResetBackToEmailSent(originalApp.id))
      } yield Actioned(originalApp)
    )
      .fold[Result](identity, identity)
  }
}
