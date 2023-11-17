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

import java.time.{Clock, LocalDateTime}
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import com.google.inject.Singleton

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Environment, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.db.{Notification, NotificationStatus, NotificationType, StoredApplication}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository}

@Singleton
class ProductionCredentialsRequestExpiryWarningJob @Inject() (
    productionCredentialsRequestExpiryWarningLockService: ProductionCredentialsRequestExpiryWarningJobLockService,
    applicationRepository: ApplicationRepository,
    notificationRepository: NotificationRepository,
    emailConnector: EmailConnector,
    clock: Clock,
    jobConfig: ProductionCredentialsRequestExpiryWarningJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  val productionCredentialsRequestExpiryWarningInterval: FiniteDuration = jobConfig.warningInterval
  override def name: String                                             = "ProductionCredentialsRequestExpiryWarningJob"
  override def interval: FiniteDuration                                 = jobConfig.interval
  override def initialDelay: FiniteDuration                             = jobConfig.initialDelay
  override val isEnabled: Boolean                                       = jobConfig.enabled
  override val lockService: LockService                                 = productionCredentialsRequestExpiryWarningLockService
  implicit val hc: HeaderCarrier                                        = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val warningTime: LocalDateTime = LocalDateTime.now(clock).minusDays(productionCredentialsRequestExpiryWarningInterval.toDays.toInt)
    logger.info(s"Send production credentials request expiry warning email for production applications having status of TESTING with updatedOn earlier than $warningTime")

    val result: Future[RunningOfJobSuccessful.type] = for {
      warningApps <-
        applicationRepository.fetchByStatusDetailsAndEnvironmentNotAleadyNotified(state = State.TESTING, updatedBefore = warningTime, environment = Environment.PRODUCTION)
      _            = logger.info(s"Scheduled job $name found ${warningApps.size} applications")
      _           <- Future.sequence(warningApps.map(sendWarningEmail))
    } yield RunningOfJobSuccessful

    result.recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }

  private def sendWarningEmail(app: StoredApplication) = {
    logger.info(s"Send production credentials request expiry warning email for app{id=${app.id.value},name=${app.name},state." +
      s"name='${app.state.name}',state.updatedOn='${app.state.updatedOn}}'")

    val recipients = getRecipients(app)
    for {
      sent <- emailConnector.sendProductionCredentialsRequestExpiryWarning(app.name, recipients)
      _    <- notificationRepository.createEntity(Notification(
                app.id,
                LocalDateTime.now(clock),
                NotificationType.PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING,
                NotificationStatus.SENT
              ))
    } yield sent
  }

  private def getRecipients(app: StoredApplication): Set[LaxEmailAddress] = {
    app.collaborators.map(_.emailAddress)
  }
}

class ProductionCredentialsRequestExpiryWarningJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "ProductionCredentialsRequestExpiryWarningScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class ProductionCredentialsRequestExpiryWarningJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, warningInterval: FiniteDuration)
