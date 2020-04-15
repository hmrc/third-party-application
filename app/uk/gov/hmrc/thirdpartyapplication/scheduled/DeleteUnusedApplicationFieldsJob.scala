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
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class DeleteUnusedApplicationFieldsJob @Inject()(val lockKeeper: DeleteUnusedApplicationFieldsJobLockKeeper,
                                                 applicationRepository: ApplicationRepository)(implicit val ec: ExecutionContext) extends ScheduledMongoJob {

  override def name: String = "DeleteUnusedApplicationFieldsJob"
  override def interval: FiniteDuration = FiniteDuration(24, TimeUnit.HOURS)
  override def initialDelay: FiniteDuration = FiniteDuration(3, TimeUnit.MINUTES)


  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info(s"Running DeleteUnusedApplicationFieldsJob")
    applicationRepository.processAll(deleteUnusedApplicationFields)
      .map(_ => RunningOfJobSuccessful)
      .recoverWith {
        case NonFatal(e) =>
          Logger.error(s"An error occurred deleting unused application fields: ${e.getMessage}", e)
          Future.failed(RunningOfJobFailed(name, e))
      }
  }

  def deleteUnusedApplicationFields(application: ApplicationData): Future[Unit] = {
    def removeSingleField(fieldName: String): Future[ApplicationData] =
      applicationRepository.updateApplication(application.id, Json.obj("$unset" -> Json.obj(fieldName -> "")))

    def removeSecretFieldFromClientSecrets() =
      Future.sequence(
        application.tokens.production.clientSecrets.indices
          .map(i => removeSingleField(s"tokens.production.clientSecrets.$i.secret")))

    val removeWso2UsernameField = removeSingleField("wso2Username")
    val removeWso2PasswordField = removeSingleField("wso2Password")
    val removeSandboxToken = removeSingleField("tokens.sandbox")
    val removeSecretFields = removeSecretFieldFromClientSecrets()

    for {
      _ <- removeWso2UsernameField
      _ <- removeWso2PasswordField
      _ <- removeSandboxToken
      _ <- removeSecretFields
    } yield ()
  }

}

class DeleteUnusedApplicationFieldsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
  override def lockId: String = "DeleteUnusedApplicationFields"
  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
}