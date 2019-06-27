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

import java.util.UUID
import java.util.concurrent.TimeUnit.{HOURS, SECONDS}

import org.joda.time.Duration
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.models.{APIIdentifier, HasSucceeded}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.scheduled.{PurgeApplicationsJob, PurgeApplicationsJobConfig, PurgeApplicationsJobLockKeeper}

import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class PurgeApplicationsJobSpec extends UnitSpec with MockitoSugar with MongoSpecSupport {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  trait Setup {
    val expectedApplications: ListMap[UUID, Seq[APIIdentifier]] = ListMap(
      UUID.fromString("a9633b5b-aae9-4419-8aa1-6317832dc580") -> Seq(APIIdentifier("hello", "1.0"), APIIdentifier("hello", "2.0")),
      UUID.fromString("73d33f9f-6e42-4a22-ae1e-5a05ba2be22d") -> Seq(APIIdentifier("example", "1.0"), APIIdentifier("example", "2.0"))
    )
    val expectedSubscriptions: Seq[Seq[APIIdentifier]] = expectedApplications.values.toSeq

    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
    val mockStateHistoryRepository: StateHistoryRepository = mock[StateHistoryRepository]
    val mockSubscriptionRepository: SubscriptionRepository = mock[SubscriptionRepository]

    when(mockApplicationRepository.delete(any[UUID])).thenReturn(successful(HasSucceeded))
    when(mockStateHistoryRepository.deleteByApplicationId(any[UUID])).thenReturn(successful(HasSucceeded))
    when(mockSubscriptionRepository.getSubscriptions(any[UUID]))
      .thenReturn(successful(expectedSubscriptions.head))
      .thenReturn(successful(expectedSubscriptions(1)))
    when(mockSubscriptionRepository.remove(any[UUID], any[APIIdentifier])).thenReturn(successful(HasSucceeded))

    val lockKeeperSuccess: () => Boolean = () => true

    val mockLockKeeper: PurgeApplicationsJobLockKeeper = new PurgeApplicationsJobLockKeeper(reactiveMongoComponent) {
      override def lockId: String = "testLock"

      override def repo: LockRepository = mock[LockRepository]

      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number

      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => successful(Some(value)))
        else successful(None)
    }

    val initialDelay = FiniteDuration(60, SECONDS) // scalastyle:off magic.number
    val interval = FiniteDuration(24, HOURS) // scalastyle:off magic.number
    val config = PurgeApplicationsJobConfig(initialDelay, interval, enabled = true)

    val underTest = new PurgeApplicationsJob(mockLockKeeper, config, mockApplicationRepository, mockStateHistoryRepository, mockSubscriptionRepository)
  }

  "PurgeApplicationsJob" should {
    "purge the applications" in new Setup {
      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe "PurgeApplicationsJob Job ran successfully."
      expectedApplications.foreach { app =>
        verify(mockApplicationRepository, times(1)).delete(app._1)
        verify(mockStateHistoryRepository, times(1)).deleteByApplicationId(app._1)
        app._2.foreach { sub =>
          verify(mockSubscriptionRepository).remove(app._1, sub)
        }
      }
      verifyNoMoreInteractions(mockApplicationRepository)
      verifyNoMoreInteractions(mockStateHistoryRepository)
    }

    "fail gracefully when it fails to delete an application" in new Setup {
      val underlyingErrorMessage: String = "Something bad happened"
      val expectedErrorMessage =
        s"The execution of scheduled job PurgeApplicationsJob failed with error '$underlyingErrorMessage'. The next execution of the job will do retry."
      when(mockApplicationRepository.delete(any[UUID])).thenReturn(failed(new RuntimeException(underlyingErrorMessage)))

      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe expectedErrorMessage
    }

    "fail gracefully when it fails to delete state history" in new Setup {
      val underlyingErrorMessage: String = "Something bad happened"
      val expectedErrorMessage =
        s"The execution of scheduled job PurgeApplicationsJob failed with error '$underlyingErrorMessage'. The next execution of the job will do retry."
      when(mockStateHistoryRepository.deleteByApplicationId(any[UUID])).thenReturn(failed(new RuntimeException(underlyingErrorMessage)))

      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe expectedErrorMessage
    }

    "fail gracefully when it fails to delete a subscription" in new Setup {
      val underlyingErrorMessage: String = "Something bad happened"
      val expectedErrorMessage =
        s"The execution of scheduled job PurgeApplicationsJob failed with error '$underlyingErrorMessage'. The next execution of the job will do retry."
      when(mockSubscriptionRepository.remove(any[UUID], any[APIIdentifier])).thenReturn(failed(new RuntimeException(underlyingErrorMessage)))

      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe expectedErrorMessage
    }
  }
}
