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

import javax.inject.{Singleton, Inject}
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.metrix.MetricOrchestrator

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}

import scala.language.postfixOps

@Singleton
class MetricsJob @Inject()(val lockKeeper: MetricsJobLockKeeper,
                           metricOrchestrator: MetricOrchestrator) extends ScheduledMongoJob {

  override def name: String = "MetricsJob"

  // TODO: Load these from config
  override def interval: FiniteDuration = 1 hour
  override def initialDelay: FiniteDuration = 2 minutes

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info(s"Running Metrics Collection Process")
    metricOrchestrator
      .attemptToUpdateAndRefreshMetrics()
      .map(result => {
        result.andLogTheResult()
        RunningOfJobSuccessful
      })
      .recoverWith {
        case e: RuntimeException => {
          Logger.error(s"An error occurred processing metrics: ${e.getMessage}", e)
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
