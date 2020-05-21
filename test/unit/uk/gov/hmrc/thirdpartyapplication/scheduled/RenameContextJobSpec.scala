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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.concurrent.TimeUnit.{HOURS, SECONDS}

import org.joda.time.{DateTime, Duration}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{RenameContextJob, RenameContextJobConfig, RenameContextJobLockKeeper}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class RenameContextJobSpec extends AsyncHmrcSpec with MongoSpecSupport {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  trait Setup {
    val mockSubscriptionRepository = mock[SubscriptionRepository]
    val lockKeeperSuccess: () => Boolean = () => true

    val mockLockKeeper = new RenameContextJobLockKeeper(reactiveMongoComponent) {
      //noinspection ScalaStyle
      override def lockId: String = null
      //noinspection ScalaStyle
      override def repo: LockRepository = null
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))
        else Future.successful(None)
    }

    val initialDelay = FiniteDuration(60, SECONDS) // scalastyle:off magic.number
    val interval = FiniteDuration(24, HOURS) // scalastyle:off magic.number
    val config = RenameContextJobConfig(initialDelay, interval, enabled = true)

    import scala.concurrent.ExecutionContext.Implicits.global
    val underTest = new RenameContextJob(mockLockKeeper, mockSubscriptionRepository, config)
  }

import scala.concurrent.ExecutionContext.Implicits.global

  "rename context job execution" should {
    "rename the business rates context" in new Setup {
      when(mockSubscriptionRepository.updateContext(any[APIIdentifier], any[String]))
        .thenReturn(successful(Some(SubscriptionData(APIIdentifier("organisations/business-rates", "1.2"), Set()))))

      val result = await(underTest.execute)

      result.message shouldBe "RenameContextJob Job ran successfully."
      verify(mockSubscriptionRepository, times(1)).updateContext(APIIdentifier("business-rates", "1.2"), "organisations/business-rates")
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false

      val result = await(underTest.execute)

      result.message shouldBe "RenameContextJob did not run because repository was locked by another instance of the scheduler."
    }

    "handle error" in new Setup {
      when(mockSubscriptionRepository.updateContext(any[APIIdentifier], any[String])).thenReturn(
        failed(new RuntimeException("A failure on executing updateContext db query"))
      )
      val result = await(underTest.execute)

      result.message shouldBe
        "The execution of scheduled job RenameContextJob failed with error 'A failure on executing updateContext db query'." +
          " The next execution of the job will do retry."
    }
  }
}
