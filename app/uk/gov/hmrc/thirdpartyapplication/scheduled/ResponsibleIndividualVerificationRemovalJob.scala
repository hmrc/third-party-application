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

import java.time.Clock
import java.time.temporal.ChronoUnit.SECONDS
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import cats.implicits._
import com.google.inject.Singleton

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandDispatcher

@Singleton
class ResponsibleIndividualVerificationRemovalJob @Inject() (
    responsibleIndividualVerificationRemovalJobLockService: ResponsibleIndividualVerificationRemovalJobLockService,
    repository: ResponsibleIndividualVerificationRepository,
    commandDispatcher: ApplicationCommandDispatcher,
    val clock: Clock,
    jobConfig: ResponsibleIndividualVerificationRemovalJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger with ClockNow {

  override def name: String                 = "ResponsibleIndividualVerificationRemovalJob"
  override def interval: FiniteDuration     = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean           = jobConfig.enabled
  implicit val hc: HeaderCarrier            = HeaderCarrier()
  override val lockService: LockService     = responsibleIndividualVerificationRemovalJobLockService

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val removeIfCreatedBeforeNow                    = instant().minus(jobConfig.removalInterval.toSeconds, SECONDS)
    val result: Future[RunningOfJobSuccessful.type] = for {
      removalsDue <-
        repository.fetchByTypeStateAndAge(ResponsibleIndividualVerification.VerificationTypeToU, ResponsibleIndividualVerificationState.REMINDERS_SENT, removeIfCreatedBeforeNow)
      _            = logger.info(s"Scheduled job $name found ${removalsDue.size} records")
      _           <- Future.sequence(removalsDue.map(sendRemovalEmailAndRemoveRecord(_)))
    } yield RunningOfJobSuccessful
    result.recoverWith {
      case e: Throwable => {
        logger.error(s"Scheduled job $name failed with an exception", e)
        Future.failed(RunningOfJobFailed(name, e))
      }
    }
  }

  def sendRemovalEmailAndRemoveRecord(verificationDueForRemoval: ResponsibleIndividualVerification) = {
    val request = DeclineResponsibleIndividualDidNotVerify(verificationDueForRemoval.id.value, instant())

    logger.info(s"Responsible individual verification timed out for application ${verificationDueForRemoval.applicationName} (started at ${verificationDueForRemoval.createdOn})")
    (for {
      savedApp <- commandDispatcher.dispatch(verificationDueForRemoval.applicationId, request, Set.empty)
    } yield HasSucceeded).value
  }
}

class ResponsibleIndividualVerificationRemovalJobLockService @Inject() (repository: LockRepository)
    extends LockService {
  override val lockRepository: LockRepository = repository
  override val lockId: String                 = "ResponsibleIndividualVerificationRemovalScheduler"
  override val ttl: Duration                  = 1.hours
}

case class ResponsibleIndividualVerificationRemovalJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, removalInterval: FiniteDuration, enabled: Boolean)
