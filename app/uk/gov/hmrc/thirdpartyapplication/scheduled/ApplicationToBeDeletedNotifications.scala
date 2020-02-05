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
import org.joda.time.{DateTime, LocalDate}
import play.api.{Configuration, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationToBeDeletedNotifications @Inject()(configuration: Configuration,
                                                    applicationRepository: ApplicationRepository,
                                                    mongo: ReactiveMongoComponent)
  extends TimedJob("ApplicationToBeDeletedNotifications", configuration, mongo) {

  val notificationJobConfig = configuration.underlying.as[ApplicationToBeDeletedNotificationsConfig](name)
  val deleteJobConfig = configuration.underlying.as[DeleteUnusedApplicationsConfig]("DeleteUnusedApplications")

  val notifier: Notifier =
    if(notificationJobConfig.dryRun) {
      new LoggingNotifier(notificationJobConfig.environmentName, logger)
    } else {
      new EmailNotifier(notificationJobConfig.environmentName)
    }

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val cutoffDate: DateTime =
      DateTime.now
        .minus(deleteJobConfig.deleteApplicationsIfUnusedFor.toMillis)
        .plus(notificationJobConfig.sendNotificationsInAdvance.toMillis)

    for {
      applicationsToNotify <- applicationRepository.applicationsRequiringDeletionPendingNotification(cutoffDate)
    } yield applicationsToNotify

    Future.successful(RunningOfJobSuccessful)
  }
}

case class ApplicationToBeDeletedNotificationsConfig(sendNotificationsInAdvance: FiniteDuration,
                                                     emailServiceURL: String,
                                                     emailTemplateId: String,
                                                     environmentName: String,
                                                     dryRun: Boolean)

trait Notifier {
  trait NotificationResult
  object NotificationSent extends NotificationResult
  object NotificationFailed extends NotificationResult

  val environmentName: String
  def notifyApplicationToBeDeleted(applicationName: String, lastUsedDate: DateTime, deletionDate: LocalDate)
                                  (implicit ec: ExecutionContext, headerCarrier: HeaderCarrier): Future[NotificationResult]
}

class LoggingNotifier(override val environmentName: String, logger: LoggerLike) extends Notifier {
  override def notifyApplicationToBeDeleted(applicationName: String, lastUsedDate: DateTime, deletionDate: LocalDate)
                                           (implicit ec: ExecutionContext, headerCarrier: HeaderCarrier): Future[NotificationResult] = {
    logger.info(
      s"[DryRun] Would have sent notification that application [$applicationName] in environment [$environmentName] has not been used since [$lastUsedDate] and would be deleted on [$deletionDate]")
    Future.successful(NotificationSent)
  }
}

class EmailNotifier(override val environmentName: String) extends Notifier {
  override def notifyApplicationToBeDeleted(applicationName: String, lastUsedDate: DateTime, deletionDate: LocalDate)
                                           (implicit ec: ExecutionContext, headerCarrier: HeaderCarrier): Future[NotificationResult] = {
    Future.successful(NotificationSent)
  }
}