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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import cats.data.OptionT
import cats.implicits._
import com.google.inject.Singleton
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.approvals.services.DeclineApprovalsService
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ResponsibleIndividual, Standard}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import java.time.temporal.ChronoUnit.SECONDS
import java.time.{Clock, LocalDateTime}
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ResponsibleIndividualVerificationRemovalJob @Inject() (
    responsibleIndividualVerificationRemovalJobLockService: ResponsibleIndividualVerificationRemovalJobLockService,
    repository: ResponsibleIndividualVerificationRepository,
    submissionsService: SubmissionsService,
    emailConnector: EmailConnector,
    applicationRepository: ApplicationRepository,
    declineApprovalsService: DeclineApprovalsService,
    val clock: Clock,
    jobConfig: ResponsibleIndividualVerificationRemovalJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  override def name: String                 = "ResponsibleIndividualVerificationRemovalJob"
  override def interval: FiniteDuration     = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean           = jobConfig.enabled
  implicit val hc: HeaderCarrier            = HeaderCarrier()
  override val lockService: LockService     = responsibleIndividualVerificationRemovalJobLockService

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val removeIfCreatedBeforeNow                    = LocalDateTime.now(clock).minus(jobConfig.removalInterval.toSeconds, SECONDS)
    val result: Future[RunningOfJobSuccessful.type] = for {
      removalsDue <- repository.fetchByTypeStateAndAge(ResponsibleIndividualVerification.VerificationTypeToU, ResponsibleIndividualVerificationState.REMINDERS_SENT, removeIfCreatedBeforeNow)
      _           <- Future.sequence(removalsDue.map(sendRemovalEmailAndRemoveRecord(_)))
    } yield RunningOfJobSuccessful
    result.recoverWith {
      case e: Throwable => {
        logger.error(s"Scheduled job $name failed with an exception", e)
        Future.failed(RunningOfJobFailed(name, e))
      }
    }
  }

  private def sendRemovalEmailAndRemoveRecord(verificationDueForRemoval: ResponsibleIndividualVerification) = {
    val declineReason = "The responsible individual did not accept the terms of use in 20 days."
    (for {
      app            <- OptionT(applicationRepository.fetch(verificationDueForRemoval.applicationId))
      ri             <- OptionT.fromOption[Future](getResponsibleIndividual(app))
      requesterName  <- OptionT.fromOption[Future](getRequesterName(app))
      requesterEmail <- OptionT.fromOption[Future](getRequesterEmail(app))
      extSubmission  <- OptionT(submissionsService.fetch(verificationDueForRemoval.submissionId))
      _              <- OptionT.liftF(declineApprovalsService.decline(app, extSubmission.submission, ri.emailAddress.value, declineReason))
      _              <- OptionT.liftF(emailConnector.sendResponsibleIndividualDidNotVerify(ri.fullName.value, requesterEmail, app.name, requesterName))
      _              <- OptionT.liftF(repository.delete(verificationDueForRemoval.id))
    } yield HasSucceeded).value
  }

  private def getResponsibleIndividual(app: ApplicationData): Option[ResponsibleIndividual] = {
    app.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => Some(importantSubmissionData.responsibleIndividual)
      case _                                                      => None
    }
  }

  private def getRequesterName(app: ApplicationData): Option[String] = {
    app.state.requestedByName
  }

  private def getRequesterEmail(app: ApplicationData): Option[String] = {
    app.state.requestedByEmailAddress
  }
}

class ResponsibleIndividualVerificationRemovalJobLockService @Inject() (repository: LockRepository)
    extends LockService {
  override val lockRepository: LockRepository = repository
  override val lockId: String                 = "ResponsibleIndividualVerificationRemovalScheduler"
  override val ttl: Duration                  = 1.hours
}

case class ResponsibleIndividualVerificationRemovalJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, removalInterval: FiniteDuration, enabled: Boolean)
