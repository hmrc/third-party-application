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

import javax.inject.{Inject, Singleton}
import net.ceedubs.ficus.Ficus._
import org.joda.time.DateTime
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import scala.concurrent.Future.successful
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeleteUnusedApplications @Inject()(configuration: Configuration,
                                         applicationService: ApplicationService,
                                         applicationRepository: ApplicationRepository,
                                         mongo: ReactiveMongoComponent) extends TimedJob("DeleteUnusedApplications", configuration, mongo) {

  private val deleteUnusedApplicationsConfig: DeleteUnusedApplicationsConfig = configuration.underlying.as[DeleteUnusedApplicationsConfig](name)

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logger.info(s"Retrieving Applications not used since ${deleteUnusedApplicationsConfig.cutoffDate}")

    for {
      applicationsToDelete <- applicationRepository.applicationsLastUsedBefore(deleteUnusedApplicationsConfig.cutoffDate)
      _ = logger.info(s"Found ${applicationsToDelete.size} applications to delete")
      _ <- deleteApplications(applicationsToDelete, deleteUnusedApplicationsConfig.dryRun)
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

case class DeleteUnusedApplicationsConfig(cutoff: FiniteDuration, dryRun: Boolean) {
  def cutoffDate: DateTime = DateTime.now.minus(cutoff.toMillis)
}
