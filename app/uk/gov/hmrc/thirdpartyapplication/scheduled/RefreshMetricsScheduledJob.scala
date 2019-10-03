/*
 * Copyright 2019 HM Revenue & Customs
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

import javax.inject.Inject
import org.joda.time.Duration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.metrix.MetricOrchestrator

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

class RefreshMetricsScheduledJob @Inject()(val lockKeeper: RefreshMetricsJobLockKeeper,
                                           jobConfig: RefreshMetricsJobConfig,
                                           val metricsOrchestrator: MetricOrchestrator) extends ScheduledMongoJob {

  override def name: String = "RefreshMetricsScheduledJob"

  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override def interval: FiniteDuration = jobConfig.interval

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] =
    metricsOrchestrator.attemptToUpdateAndRefreshMetrics().map(_.andLogTheResult()).map(_ => RunningOfJobSuccessful)

}

class RefreshMetricsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
  override def lockId: String = "RefreshMetricsScheduledJob"
  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(1)
}

case class RefreshMetricsJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)