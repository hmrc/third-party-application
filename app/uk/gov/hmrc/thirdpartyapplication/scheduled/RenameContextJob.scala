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

import com.google.inject.Singleton
import javax.inject.Inject
import org.joda.time.Duration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
@scala.deprecated("this needs removing once it's run in prod")
class RenameContextJob @Inject()(val lockKeeper: RenameContextJobLockKeeper,
                                 subscriptionRepository: SubscriptionRepository,
                                 jobConfig: RenameContextJobConfig)
                                (implicit val ec: ExecutionContext) extends ScheduledMongoJob {

  override def name: String = "RenameContextJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean = jobConfig.enabled

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    subscriptionRepository.updateContext(APIIdentifier("business-rates", "1.2"), "organisations/business-rates") map { _ =>
      RunningOfJobSuccessful
    } recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }
}

class RenameContextJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "RenameContextJob"

  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(60) // scalastyle:off magic.number
}

case class RenameContextJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)
