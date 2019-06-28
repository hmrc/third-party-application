/*
 * Copyright 2019 HM Revenue & Customs
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

import javax.inject.Inject
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.models.{APIIdentifier, HasSucceeded}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}

import scala.collection.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.{failed, sequence}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class PurgeApplicationsJob @Inject()(override val lockKeeper: PurgeApplicationsJobLockKeeper,
                                     jobConfig: PurgeApplicationsJobConfig,
                                     applicationRepository: ApplicationRepository,
                                     stateHistoryRepository: StateHistoryRepository,
                                     subscriptionRepository: SubscriptionRepository) extends ScheduledMongoJob {

  override def name: String = "PurgeApplicationsJob"

  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override def interval: FiniteDuration = jobConfig.interval

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info("Starting PurgeApplicationsJob")
    countRecords()
    val applicationIds: Seq[UUID] = Seq(
      UUID.fromString("a9633b5b-aae9-4419-8aa1-6317832dc580"),
      UUID.fromString("73d33f9f-6e42-4a22-ae1e-5a05ba2be22d")
    )

    purgeApplications(applicationIds) map { _ =>
      Logger.info(s"Purged applications: $applicationIds")
      countRecords()
      RunningOfJobSuccessful
    } recoverWith {
      case e: Throwable => failed(RunningOfJobFailed(name, e))
    }
  }

  private def countRecords(): Unit = {
    applicationRepository.count.map(i => Logger.info(s"Applications: $i"))
    stateHistoryRepository.count.map(i => Logger.info(s"State history records: $i"))
    subscriptionRepository.count.map(i => Logger.info(s"Subscriptions: $i"))
  }

  private def purgeApplications(applicationIds: Seq[UUID]): Future[Seq[RunningOfJobSuccessful]] = {
    sequence {
      applicationIds map { applicationId =>
        for {
          _ <- applicationRepository.delete(applicationId)
          _ <- stateHistoryRepository.deleteByApplicationId(applicationId)
          subscriptions <- subscriptionRepository.getSubscriptions(applicationId)
          _ <- deleteSubscriptions(applicationId, subscriptions)
        } yield RunningOfJobSuccessful
      }
    }
  }

  private def deleteSubscriptions(applicationId: UUID, subscriptions: Seq[APIIdentifier]): Future[Seq[HasSucceeded]] = {
    sequence(subscriptions.map(sub => subscriptionRepository.remove(applicationId, sub)))
  }
}

class PurgeApplicationsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "PurgeApplicationsJobConfig"

  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(10) //scalastyle:ignore magic.number
}

case class PurgeApplicationsJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)
