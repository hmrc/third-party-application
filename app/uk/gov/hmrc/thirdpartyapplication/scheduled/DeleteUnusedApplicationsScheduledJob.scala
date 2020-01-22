/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.UUID

import javax.inject.Inject
import org.joda.time.{DateTime, Duration}
import play.api.{Logger, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import scala.concurrent.Future.successful
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class DeleteUnusedApplicationsScheduledJob @Inject()(val lockKeeper: DeleteUnusedApplicationsJobLockKeeper,
                                                     applicationService: ApplicationService,
                                                     applicationRepository: ApplicationRepository,
                                                     jobConfig: DeleteUnusedApplicationsJobConfig,
                                                     logger: LoggerLike = Logger) extends ScheduledMongoJob {

  override def name: String = "DeleteUnusedApplicationsScheduledJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    logger.info("Starting Delete Unused Application Job")

    val cutoffDate: DateTime = DateTime.now.minus(jobConfig.cutoff.toMillis)
    Logger.info(s"Retrieving Applications not used since $cutoffDate")

    for {
      applicationsToDelete <- applicationRepository.applicationsLastUsedBefore(cutoffDate)
      _ = logger.info(s"Found ${applicationsToDelete.size} applications to delete")
      _ <- deleteApplications(applicationsToDelete, jobConfig.dryRun)
    } yield RunningOfJobSuccessful
  }
  
  def deleteApplications(applicationIds: Set[UUID], dryRun: Boolean)(implicit ec: ExecutionContext): Future[HasSucceeded] = {
    def audit(app: ApplicationData): Future[AuditResult] = {
      logger.info(s"Deleting application ${app.id} - ${app.name}")
      successful(uk.gov.hmrc.play.audit.http.connector.AuditResult.Success)
    }

    if (dryRun) {
      applicationIds.foreach(applicationId => logger.info(s"[Dry Run] Would have deleted application with id [${applicationId.toString}]"))
      Future.successful(HasSucceeded)
    } else {
      Future.sequence(applicationIds.map { applicationId =>
        applicationService.deleteApplication(applicationId, None, audit)(HeaderCarrier())
      }).map(_ => HasSucceeded)
    }
  }
}

class DeleteUnusedApplicationsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
  override def lockId: String = "DeleteUnusedApplicationsScheduledJob"
  override val forceLockReleaseAfter: Duration = Duration.standardHours(1)

}

case class DeleteUnusedApplicationsJobConfig(initialDelay: FiniteDuration,
                                             interval: FiniteDuration,
                                             enabled: Boolean,
                                             cutoff: FiniteDuration,
                                             dryRun: Boolean = true)