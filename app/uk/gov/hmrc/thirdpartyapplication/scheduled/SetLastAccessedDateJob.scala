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
import org.joda.time.{DateTime, Duration}
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class SetLastAccessedDateJob @Inject()(val lockKeeper: SetLastAccessedDateJobLockKeeper,
                                       jobConfig: SetLastAccessedDateJobConfig,
                                       applicationRepository: ApplicationRepository) extends ScheduledMongoJob {

  override def name: String = "SetLastAccessedDate"

  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override def interval: FiniteDuration = jobConfig.interval

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info("Starting SetLastAccessDateJob")

    // Today's date with zeroed-out time
    val dateToSet = DateTime.now().withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)

    applicationRepository
      .setMissingLastAccessedDates(dateToSet)
      .map { numberOfApplicationsUpdated =>
        Logger.info(s"Set lastAccess field to $dateToSet on $numberOfApplicationsUpdated Applications")
        RunningOfJobSuccessful
      }
      .recoverWith {
        case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
      }
  }
}

class SetLastAccessedDateJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "SetLastAccessedDateJob"

  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(10) //scalastyle:ignore magic.number
}

case class SetLastAccessedDateJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, validity: FiniteDuration)