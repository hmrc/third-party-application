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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.concurrent.TimeUnit.{DAYS, SECONDS}

import common.uk.gov.hmrc.thirdpartyapplication.common.LogSuppressing
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.{DateTime, DateTimeUtils, Duration}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.scheduled.{RefreshSubscriptionsJobConfig, RefreshSubscriptionsJobLockKeeper, RefreshSubscriptionsScheduledJob}
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class RefreshSubscriptionsScheduledJobSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with BeforeAndAfterAll with ApplicationStateUtil
  with LogSuppressing {

  val FixedTimeNow: DateTime = HmrcTime.now
  val expiryTimeInDays = 90

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val mockSubscriptionService: SubscriptionService = mock[SubscriptionService]

    val lockKeeperSuccess: () => Boolean = () => true

    private val reactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

    val mockLockKeeper: RefreshSubscriptionsJobLockKeeper = new RefreshSubscriptionsJobLockKeeper(reactiveMongoComponent) {
      override def lockId: String = "testLock"

      override def repo: LockRepository = mock[LockRepository]

      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number

      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Future.successful(Some(value)))
        else Future.successful(None)
    }

    val config = RefreshSubscriptionsJobConfig(FiniteDuration(120, SECONDS), FiniteDuration(60, DAYS), enabled = true) // scalastyle:off magic.number
    val underTest = new RefreshSubscriptionsScheduledJob(mockLockKeeper, mockSubscriptionService, config)
  }

  override def beforeAll(): Unit = {
    DateTimeUtils.setCurrentMillisFixed(FixedTimeNow.toDate.getTime)
  }

  override def afterAll(): Unit = {
    DateTimeUtils.setCurrentMillisSystem()
  }

  "refresh subscriptions job execution" should {
    "attempt to refresh the subscriptions for all applications" in new Setup {
      when(mockSubscriptionService.refreshSubscriptions()(*)).thenReturn(Future.successful(5))

      await(underTest.execute)
      verify(mockSubscriptionService, times(1)).refreshSubscriptions()(*)
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false

      await(underTest.execute)
    }

    "handle error when fetching subscription fails" in new Setup {
      withSuppressedLoggingFrom(Logger, "Could not refresh subscriptions") { _ =>
        when(mockSubscriptionService.refreshSubscriptions()(*)).thenReturn(
          Future.failed(new RuntimeException("A failure on executing refreshSubscriptions"))
        )
        val result = await(underTest.execute)

        result.message shouldBe
          "The execution of scheduled job RefreshSubscriptionsScheduledJob failed with error" +
          " 'A failure on executing refreshSubscriptions'. The next execution of the job will do retry."
      }
    }
  }
}
