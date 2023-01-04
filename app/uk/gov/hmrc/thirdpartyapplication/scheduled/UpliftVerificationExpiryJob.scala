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

import com.google.inject.Singleton
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType.SCHEDULED_JOB
import uk.gov.hmrc.thirdpartyapplication.domain.models.{OldActor, State, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}

import java.time.{Clock, LocalDateTime}
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpliftVerificationExpiryJob @Inject() (
    upliftVerificationExpiryJobLockService: UpliftVerificationExpiryJobLockService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    clock: Clock,
    jobConfig: UpliftVerificationExpiryJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  val upliftVerificationValidity: FiniteDuration = jobConfig.validity
  val sixty                                      = 60
  override def name: String                      = "UpliftVerificationExpiryJob"
  override def interval: FiniteDuration          = jobConfig.interval
  override def initialDelay: FiniteDuration      = jobConfig.initialDelay
  override val isEnabled: Boolean                = jobConfig.enabled
  override val lockService: LockService          = upliftVerificationExpiryJobLockService

  private def transitionAppBackToTesting(app: ApplicationData): Future[ApplicationData] = {
    logger.info(s"Set status back to testing for app{id=${app.id.value},name=${app.name},state." +
      s"requestedByEmailAddress='${app.state.requestedByEmailAddress.getOrElse("")}',state.updatedOn='${app.state.updatedOn}}'")
    for {
      updatedApp <- applicationRepository.save(app.copy(state = app.state.toTesting(clock)))
      _          <- stateHistoryRepository.insert(StateHistory(
                      app.id,
                      State.TESTING,
                      OldActor("UpliftVerificationExpiryJob", SCHEDULED_JOB),
                      Some(State.PENDING_REQUESTER_VERIFICATION),
                      changedAt = LocalDateTime.now(clock)
                    ))
    } yield updatedApp
  }

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val expiredTime: LocalDateTime = LocalDateTime.now(clock).minusDays(upliftVerificationValidity.toDays.toInt)
    logger.info(s"Move back applications to TESTING having status 'PENDING_REQUESTER_VERIFICATION' with timestamp earlier than $expiredTime")

    val result: Future[RunningOfJobSuccessful.type] = for {
      expiredApps <- applicationRepository.fetchAllByStatusDetails(state = State.PENDING_REQUESTER_VERIFICATION, updatedBefore = expiredTime)
      _            = logger.info(s"Found ${expiredApps.size} applications")
      _           <- Future.sequence(expiredApps.map(transitionAppBackToTesting))
    } yield RunningOfJobSuccessful

    result.recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }
}

class UpliftVerificationExpiryJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "UpliftVerificationExpiryScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class UpliftVerificationExpiryJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, validity: FiniteDuration)
