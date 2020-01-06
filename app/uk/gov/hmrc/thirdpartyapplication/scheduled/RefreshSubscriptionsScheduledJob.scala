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

import javax.inject.Inject
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class RefreshSubscriptionsScheduledJob @Inject()(val lockKeeper: RefreshSubscriptionsJobLockKeeper,
                                                 subscriptionService: SubscriptionService,
                                                 jobConfig: RefreshSubscriptionsJobConfig) extends ScheduledMongoJob {

  override def name: String = "RefreshSubscriptionsScheduledJob"

  override def interval: FiniteDuration = jobConfig.interval

  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    Logger.info("Starting RefreshSubscriptionsJob")

    subscriptionService.refreshSubscriptions() map { modified =>
      Logger.info(s"$modified subscriptions have been refreshed")
      RunningOfJobSuccessful
    } recoverWith {
      case e: Throwable =>
        Logger.error("Could not refresh subscriptions", e)
        Future.failed(RunningOfJobFailed(name, e))
    }
  }
}

class RefreshSubscriptionsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "RefreshSubscriptionsScheduledJob"

  override val forceLockReleaseAfter: Duration = Duration.standardHours(2)

}

case class RefreshSubscriptionsJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)
