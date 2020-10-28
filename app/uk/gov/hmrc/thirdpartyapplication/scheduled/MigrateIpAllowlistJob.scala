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

import java.util.concurrent.TimeUnit

import javax.inject.Inject
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.models.IpAllowlist
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@deprecated("Remove once the migration is complete","?")
class MigrateIpAllowlistJob @Inject()(val lockKeeper: MigrateIpAllowlistJobLockKeeper,
                                       applicationRepository: ApplicationRepository,
                                       jobConfig: MigrateIpAllowlistJobConfig) extends ScheduledMongoJob {

  override def name: String = "MigrateIpAllowlistJob"
  override def isEnabled: Boolean = jobConfig.enabled
  override def initialDelay: FiniteDuration = FiniteDuration(5, TimeUnit.MINUTES)
  override def interval: FiniteDuration = FiniteDuration(24, TimeUnit.HOURS)

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    applicationRepository.processAll(migrateIpAllowlist)
      .map(_ => RunningOfJobSuccessful)
      .recoverWith {
        case NonFatal(e) =>
          Logger.error(e.getMessage, e)
          Future.failed(RunningOfJobFailed(name, e))
      }
  }

  private def migrateIpAllowlist(app: ApplicationData): Unit = {
    if (app.ipWhitelist.nonEmpty) {
      applicationRepository.updateApplicationIpAllowlist(app.id, IpAllowlist(required = false, app.ipWhitelist))
    } else {
      applicationRepository.updateApplicationIpAllowlist(app.id, IpAllowlist())
    }
  }
}

class MigrateIpAllowlistJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
  override def lockId: String = "MigrateIpAllowlistJob"
  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(60) // scalastyle:off magic.number
}

case class MigrateIpAllowlistJobConfig(enabled: Boolean)
