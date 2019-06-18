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

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.{HOURS, SECONDS}

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.{DateTime, Duration}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{SetLastAccessedDateJob, SetLastAccessedDateJobConfig, SetLastAccessedDateJobLockKeeper}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class SetLastAccessedDateJobSpec extends UnitSpec with MockitoSugar with MongoSpecSupport with BeforeAndAfterAll with ApplicationStateUtil {

  val FixedTimeNow: DateTime = DateTime.now
  val expiryTimeInDays = 90

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  trait Setup {
    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]

    val lockKeeperSuccess: () => Boolean = () => true

    val mockLockKeeper: SetLastAccessedDateJobLockKeeper = new SetLastAccessedDateJobLockKeeper(reactiveMongoComponent) {
      override def lockId: String = "testLock"

      override def repo: LockRepository = mock[LockRepository]

      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number

      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Future.successful(Some(value)))
        else Future.successful(None)
    }

    val upliftVerificationValidity = FiniteDuration(expiryTimeInDays, TimeUnit.DAYS)
    val initialDelay = FiniteDuration(60, SECONDS) // scalastyle:off magic.number
    val interval = FiniteDuration(24, HOURS) // scalastyle:off magic.number
    val config = SetLastAccessedDateJobConfig(initialDelay, interval, enabled = true, upliftVerificationValidity)

    val underTest = new SetLastAccessedDateJob(mockLockKeeper, config, mockApplicationRepository)
  }

  "Scheduled Job" should {
    "call ApplicationRepository to update lastAccess field" in new Setup {
      val expectedDateToSet: DateTime = DateTime.now().withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
      val expectedNumberOfUpdates: Int = 5

      when(mockApplicationRepository.setMissingLastAccessedDates(expectedDateToSet)).thenReturn(Future.successful(expectedNumberOfUpdates))

      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe "SetLastAccessedDate Job ran successfully."
    }

    "fail gracefully when job fails" in new Setup {
      val expectedDateToSet: DateTime = DateTime.now().withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
      val underlyingErrorMessage: String = "Something bad happened"
      val expectedErrorMessage =
        s"The execution of scheduled job SetLastAccessedDate failed with error '$underlyingErrorMessage'. The next execution of the job will do retry."

      when(mockApplicationRepository.setMissingLastAccessedDates(expectedDateToSet)).thenReturn(Future.failed(new RuntimeException(underlyingErrorMessage)))

      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe expectedErrorMessage
    }
  }
}
