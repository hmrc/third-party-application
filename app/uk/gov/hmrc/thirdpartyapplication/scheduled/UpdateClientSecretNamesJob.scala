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

import javax.inject.{Inject, Singleton}
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class UpdateClientSecretNamesJob @Inject()(val lockKeeper: UpdateClientSecretNamesJobLockKeeper,
                                             applicationRepository: ApplicationRepository) extends ScheduledMongoJob {

  override def name: String = "UpdateClientSecretNames"
  override def initialDelay: FiniteDuration = FiniteDuration(2, TimeUnit.MINUTES) // scalastyle:off magic.number
  override def interval: FiniteDuration = FiniteDuration(24, TimeUnit.HOURS) // scalastyle:off magic.number

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info(s"Running UpdateClientSecretNamesJob")
    applicationRepository.processAll(updateClientSecretNames)
      .map(_ => RunningOfJobSuccessful)
      .recoverWith {
        case NonFatal(e) =>
          Logger.error(s"An error occurred updating client secret names: ${e.getMessage}", e)
          Future.failed(RunningOfJobFailed(name, e))
      }
  }

  private def updateClientSecretNames(app: ApplicationData): Unit = {
    app.tokens.production.clientSecrets foreach { clientSecret =>
      val expectedName = clientSecret.secret.takeRight(4)
      if (clientSecret.name != expectedName) applicationRepository.updateClientSecretName(app.id, clientSecret.id, expectedName)
    }
  }
}

class UpdateClientSecretNamesJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
  override def lockId: String = "UpdateClientSecretNamesJob"
  override val forceLockReleaseAfter: Duration = Duration.standardHours(1) // scalastyle:off magic.number
}
