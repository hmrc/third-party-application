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

import com.google.inject.Singleton

import javax.inject.Inject
import org.joda.time.Duration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType.SCHEDULED_JOB
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.domain.models.StateHistory
import uk.gov.hmrc.thirdpartyapplication.domain.models.Actor
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import java.time.{Clock, LocalDateTime}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ResponsibleIndividualVerificationReminderJob @Inject()(val lockKeeper: ResponsibleIndividualVerificationReminderJobLockKeeper,
                                                             repository: ResponsibleIndividualVerificationRepository,
                                                             val clock: Clock,
                                                             jobConfig: ResponsibleIndividualVerificationReminderJobConfig)(implicit val ec: ExecutionContext) extends ScheduledMongoJob with ApplicationLogger {

  override def name: String = "ResponsibleIndividualVerificationReminderJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean = jobConfig.enabled

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val result: Future[RunningOfJobSuccessful.type] = for {
      expiredApps <- repository.findAll().map(_.foreach(r => println(r)))
    } yield RunningOfJobSuccessful
    result.recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }
}

class ResponsibleIndividualVerificationReminderJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "ResponsibleIndividualVerificationReminderScheduler"

  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(60) // scalastyle:off magic.number
}

case class ResponsibleIndividualVerificationReminderJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)
