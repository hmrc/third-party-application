/*
 * Copyright 2025 HM Revenue & Customs
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

import java.time.{Clock, Instant, ZoneId}
import javax.inject.Inject
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Updates

import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.mongo.play.json.Codecs

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator

class BulkInsertAppsJob @Inject() (
    lockRepository: MongoLockRepository,
    credentialGenerator: CredentialGenerator,
    applicationRepository: ApplicationRepository,
    subscriptionRepository: SubscriptionRepository
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  import scala.concurrent.duration._

  override lazy val lockService: LockService = LockService(lockRepository, lockId = "BulkInsertAppsJob", ttl = 2.hour)

  override def name: String = "BulkInsertAppsJob"

  override def interval: FiniteDuration = 500.hours

  override def initialDelay: FiniteDuration = 1.minute

  override val isEnabled: Boolean = true

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logger.info(s"$name - Running generate job")
    populateData()
    Future.successful(RunningOfJobSuccessful)
  }

  private def populateData(): Unit = {
    logger.info(s"$name - Populating data")

    val BatchSize       = 2000
    val NumberOfBatches = 5

    def generateRandomData(startNumber: Int, batchSize: Int) =
      (startNumber to startNumber + batchSize - 1).map(n => {
        val name                             = ApplicationName(s"FILLER_APP_$n")
        val normalisedName                   = name.value.toLowerCase
        val collaborators: Set[Collaborator] =
          Set(Collaborators.Administrator(userId = UserId.unsafeApply("0312a26f-e265-4ecb-8b38-8f9c95d95fd6"), emailAddress = LaxEmailAddress("perf@digital.hmrc.gov.uk")))
        val creationTime                     = Instant.now(Clock.tickMillis(ZoneId.systemDefault()))
        StoredApplication(
          id = ApplicationId.random,
          name = name,
          normalisedName = normalisedName,
          collaborators = collaborators,
          description = Some("API Platform Team"),
          wso2ApplicationName = credentialGenerator.generate(),
          tokens = ApplicationTokens(production = StoredToken(clientId = ClientId.random, accessToken = "")),
          state = ApplicationState(State.PRODUCTION, updatedOn = creationTime),
          createdOn = creationTime,
          lastAccess = None,
          environment = Environment.PRODUCTION,
          deleteRestriction = DeleteRestriction.DoNotDelete(reason = "Test Application", actor = Actors.AppCollaborator(collaborators.head.emailAddress), timestamp = creationTime)
        )
      })

    for (batch <- 1 to NumberOfBatches) {
      logger.info(s"$name - Starting batch $batch")

      val applications = generateRandomData((batch - 1) * BatchSize + 1, BatchSize)

      Await.ready(
        try {
          applicationRepository.collection.insertMany(applications).toFuture()
        } catch {
          case NonFatal(e) => logger.info(s"FAILED $name adding applications: " + e.getMessage()); Future.failed(e)
        },
        2.minutes
      )

      Await.ready(
        try {
          subscriptionRepository.collection.updateOne(
            filter = and(
              equal("apiIdentifier.context", Codecs.toBson("api-simulator")),
              equal("apiIdentifier.version", Codecs.toBson("1.0"))
            ),
            update = Updates.addEachToSet("applications", applications.map(app => Codecs.toBson(app.id)): _*)
          ).toFuture()
        } catch {
          case NonFatal(e) => logger.info(s"FAILED $name adding subscriptions: " + e.getMessage()); Future.failed(e)
        },
        2.minutes
      )

      logger.info(s"$name - Completed batch $batch")
    }
  }
}
