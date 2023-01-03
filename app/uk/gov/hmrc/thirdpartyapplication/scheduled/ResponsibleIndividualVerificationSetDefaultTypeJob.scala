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
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import java.time.Clock
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ResponsibleIndividualVerificationSetDefaultTypeJob @Inject() (
    responsibleIndividualVerificationSetDefaultTypeJobLockService: ResponsibleIndividualVerificationSetDefaultTypeJobLockService,
    repository: ResponsibleIndividualVerificationRepository,
    val clock: Clock,
    jobConfig: ResponsibleIndividualVerificationSetDefaultTypeJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  override def name: String                 = "ResponsibleIndividualVerificationSetDefaultTypeJob"
  override def interval: FiniteDuration     = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean           = jobConfig.enabled
  implicit val hc: HeaderCarrier            = HeaderCarrier()
  override val lockService: LockService     = responsibleIndividualVerificationSetDefaultTypeJobLockService

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val result: Future[RunningOfJobSuccessful.type] = for {
      _ <- repository.updateSetDefaultVerificationType("termsOfUse")
    } yield RunningOfJobSuccessful
    result.recoverWith {
      case e: Throwable => {
        logger.error(s"Scheduled job $name failed with an exception", e)
        Future.failed(RunningOfJobFailed(name, e))
      }
    }
  }
}

class ResponsibleIndividualVerificationSetDefaultTypeJobLockService @Inject() (repository: LockRepository)
    extends LockService {
  override val lockRepository: LockRepository = repository
  override val lockId: String                 = "ResponsibleIndividualVerificationSetDefaultTypeScheduler"
  override val ttl: Duration                  = 1.hours
}

case class ResponsibleIndividualVerificationSetDefaultTypeJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)
