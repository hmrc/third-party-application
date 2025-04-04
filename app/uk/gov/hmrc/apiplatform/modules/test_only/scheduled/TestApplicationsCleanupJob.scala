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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import java.time.{Clock, Instant}
import javax.inject.{Inject,Singleton}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import java.time.temporal.ChronoUnit
import uk.gov.hmrc.apiplatform.modules.test_only.repository.TestApplicationsRepository
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import scala.util.control.NonFatal

object TestApplicationsCleanupJob {
  case class Config(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, expiryDuration: FiniteDuration)
}

@Singleton
class TestApplicationsCleanupJob @Inject() (
    cleanupLockService: TestApplicationsCleanupJobLockService,
    testAppRepo: TestApplicationsRepository,
    applicationService: ApplicationService,
    val clock: Clock,
    jobConfig: TestApplicationsCleanupJob.Config
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ClockNow {

  override def name: String                                      = "TestApplicationsCleanupJob"
  override def interval: FiniteDuration                          = jobConfig.interval
  override def initialDelay: FiniteDuration                      = jobConfig.initialDelay
  override val isEnabled: Boolean                                = jobConfig.enabled
  override val lockService: LockService                          = cleanupLockService
  implicit val hc: HeaderCarrier                                 = HeaderCarrier()

  logger.info("TestApplicationsCleanupJob ready!!!")

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val timeBeforeWhichAppIsConsideredExpired: Instant = instant().minus(jobConfig.expiryDuration.toMinutes, ChronoUnit.MINUTES)
    logger.info(s"Delete expired test applications created earlier than $timeBeforeWhichAppIsConsideredExpired ( ${jobConfig.expiryDuration.toMinutes} mins ago)")

    val result: Future[RunningOfJobSuccessful.type] = for {
      idsToRemove <- testAppRepo.findCreatedBefore(timeBeforeWhichAppIsConsideredExpired)
      _           = logger.info(s"Scheduled job $name found ${idsToRemove.size} test applications")
      _           <- Future.sequence(idsToRemove.map(deleteExpiredApplication(_)))
    } yield RunningOfJobSuccessful

    result.recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }

  private def deleteExpiredApplication(appId: ApplicationId): Future[HasSucceeded] = {
    logger.info(s"Delete expired test application $appId.")
    val noAuditing: StoredApplication => Future[AuditResult] = _ => Future.successful(uk.gov.hmrc.play.audit.http.connector.AuditResult.Disabled)

    (for {
      _ <- applicationService.deleteApplication(appId, None, noAuditing)
      _ <- testAppRepo.delete(appId)
    } yield HasSucceeded
    ).recover {
      case NonFatal(e) =>
        logger.info(s"Failed to delete expired test application $appId",e)
        HasSucceeded
    }
  }
}

class TestApplicationsCleanupJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "TestApplicationsCleanupScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

