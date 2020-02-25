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

import java.util.UUID

import javax.inject.{Inject, Singleton}
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.control.NonFatal

@Singleton
class SetClientSecretIdJob @Inject()(val lockKeeper: SetClientSecretIdJobLockKeeper,
                                     jobConfig: SetClientSecretIdJobConfig,
                                     applicationRepository: ApplicationRepository) extends ScheduledMongoJob {

  override def name: String = "SetClientSecretIdJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info(s"Running SetClientSecretIdJob")
    applicationRepository.processAll(processApp)
      .map(_ => RunningOfJobSuccessful)
      .recoverWith {
        case NonFatal(e) =>
          Logger.error(s"An error occurred processing client secret updates: ${e.getMessage}", e)
          Future.failed(RunningOfJobFailed(name, e))
      }
  }

  private def processApp(app: ApplicationData): Unit = {
    if(app.tokens.production.clientSecrets.exists(_.id.isEmpty)) {
      val updatedClientSecrets: Seq[ClientSecret] = app.tokens.production.clientSecrets map { clientSecret =>
        clientSecret.id.fold(clientSecret.copy(id = Some(UUID.randomUUID().toString)))(_ => clientSecret)
      }
      applicationRepository.updateClientSecrets(app.id, updatedClientSecrets)
    }
  }
}

class SetClientSecretIdJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "SetClientSecretIdJob"

  override val forceLockReleaseAfter: Duration = Duration.standardHours(2)
}

case class SetClientSecretIdJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)

