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

import cats.implicits._
import com.google.inject.Singleton

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.domain.models.{Environment, State}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandDispatcher

@Singleton
class ProductionCredentialsRequestExpiredJob @Inject() (
    productionCredentialsRequestExpiredLockService: ProductionCredentialsRequestExpiredJobLockService,
    applicationRepository: ApplicationRepository,
    commandDispatcher: ApplicationCommandDispatcher,
    clock: Clock,
    jobConfig: ProductionCredentialsRequestExpiredJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  val productionCredentialsRequestDeleteInterval: FiniteDuration = jobConfig.deleteInterval
  override def name: String                                      = "ProductionCredentialsRequestExpiredJob"
  override def interval: FiniteDuration                          = jobConfig.interval
  override def initialDelay: FiniteDuration                      = jobConfig.initialDelay
  override val isEnabled: Boolean                                = jobConfig.enabled
  override val lockService: LockService                          = productionCredentialsRequestExpiredLockService
  implicit val hc: HeaderCarrier                                 = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val deleteTime: LocalDateTime = LocalDateTime.now(clock).minusDays(productionCredentialsRequestDeleteInterval.toDays.toInt)
    logger.info(s"Delete expired production credentials requests for production applications having status of TESTING with updatedOn earlier than $deleteTime")

    val result: Future[RunningOfJobSuccessful.type] = for {
      deleteApps <- applicationRepository.fetchByStatusDetailsAndEnvironment(state = State.TESTING, updatedBefore = deleteTime, environment = Environment.PRODUCTION)
      _           = logger.info(s"Found ${deleteApps.size} applications")
      _          <- Future.sequence(deleteApps.map(deleteExpiredApplication(_)))
    } yield RunningOfJobSuccessful

    result.recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }

  private def deleteExpiredApplication(app: ApplicationData) = {
    logger.info(s"Delete expired production credentials request for app{id=${app.id.value},name=${app.name},state." +
      s"name='${app.state.name}',state.updatedOn='${app.state.updatedOn}}'")

    val reasons = s"Delete expired production credentials request, updated on ${app.state.updatedOn}"
    val request = ApplicationCommands.DeleteProductionCredentialsApplication(name, reasons, LocalDateTime.now(clock))

    (for {
      savedApp <- commandDispatcher.dispatch(app.id, request, Set.empty)
    } yield HasSucceeded).value
  }
}

class ProductionCredentialsRequestExpiredJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "ProductionCredentialsRequestExpiredScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class ProductionCredentialsRequestExpiredJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, deleteInterval: FiniteDuration)
