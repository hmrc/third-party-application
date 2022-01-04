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

import javax.inject.{Inject, Singleton}
import org.joda.time.Duration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.metrix.MetricOrchestrator
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationLogger

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetricsJob @Inject()(val lockKeeper: MetricsJobLockKeeper,
                           metricOrchestrator: MetricOrchestrator,
                           jobConfig: MetricsJobConfig)
                          (implicit val ec: ExecutionContext) extends ScheduledMongoJob with ApplicationLogger {

  override def name: String = "MetricsJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean = jobConfig.enabled

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logger.info(s"Running Metrics Collection Process")
    metricOrchestrator
      .attemptToUpdateAndRefreshMetrics()
      .map(result => {
        result.andLogTheResult()
        RunningOfJobSuccessful
      })
      .recoverWith {
        case e: RuntimeException => {
          logger.error(s"An error occurred processing metrics: ${e.getMessage}", e)
          Future.failed(RunningOfJobFailed(name, e))
        }
      }
  }
}

class MetricsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "MetricsJob"

  override val forceLockReleaseAfter: Duration = Duration.standardHours(2)
}

case class MetricsJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)

