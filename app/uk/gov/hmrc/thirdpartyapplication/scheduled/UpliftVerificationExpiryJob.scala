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
import org.joda.time.{DateTime, Duration}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType.SCHEDULED_JOB
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.domain.models.StateHistory
import uk.gov.hmrc.thirdpartyapplication.domain.models.Actor
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationLogger

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpliftVerificationExpiryJob @Inject()(val lockKeeper: UpliftVerificationExpiryJobLockKeeper,
                                            applicationRepository: ApplicationRepository,
                                            stateHistoryRepository: StateHistoryRepository,
                                            jobConfig: UpliftVerificationExpiryJobConfig)(implicit val ec: ExecutionContext) extends ScheduledMongoJob with ApplicationLogger {

  val upliftVerificationValidity: FiniteDuration = jobConfig.validity

  override def name: String = "UpliftVerificationExpiryJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean = jobConfig.enabled

  private def transitionAppBackToTesting(app: ApplicationData): Future[ApplicationData] = {
    logger.info(s"Set status back to testing for app{id=${app.id.value},name=${app.name},state." +
      s"requestedByEmailAddress='${app.state.requestedByEmailAddress.getOrElse("")}',state.updatedOn='${app.state.updatedOn}}'")
    for {
      updatedApp <- applicationRepository.save(app.copy(state = app.state.toTesting))
      _ <- stateHistoryRepository.insert(StateHistory(app.id, State.TESTING,
        Actor("UpliftVerificationExpiryJob", SCHEDULED_JOB), Some(State.PENDING_REQUESTER_VERIFICATION)))
    } yield updatedApp
  }

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val expiredTime: DateTime = DateTimeUtils.now.minusDays(upliftVerificationValidity.toDays.toInt)
    logger.info(s"Move back applications to TESTING having status 'PENDING_REQUESTER_VERIFICATION' with timestamp earlier than $expiredTime")
    val result: Future[RunningOfJobSuccessful.type] = for {
      expiredApps <- applicationRepository.fetchAllByStatusDetails(state = State.PENDING_REQUESTER_VERIFICATION, updatedBefore = expiredTime)
      _ = logger.info(s"Found ${expiredApps.size} applications")
      _ <- Future.sequence(expiredApps.map(transitionAppBackToTesting))
    } yield RunningOfJobSuccessful
    result.recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }
}

class UpliftVerificationExpiryJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "UpliftVerificationExpiryScheduler"

  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(60) // scalastyle:off magic.number
}

case class UpliftVerificationExpiryJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, validity: FiniteDuration)
