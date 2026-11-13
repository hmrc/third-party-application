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

package uk.gov.hmrc.apiplatform.modules.scheduling

import java.util.concurrent.CountDownLatch
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.mongo.lock.{Lock, MongoLockRepository}

import uk.gov.hmrc.apiplatform.modules.common.utils.{FixedClock, HmrcSpec}

class LockedScheduledJobSpec extends HmrcSpec with ScalaFutures with GuiceOneAppPerTest with BeforeAndAfterEach with FixedClock {

  trait FakeService {
    def call(): String
  }

  class FakeServiceJob(service: FakeService, val mongoLockRepository: MongoLockRepository) extends ExclusiveLockedScheduledJob {
    val name: String                 = "FakeServiceJob"
    val interval: FiniteDuration     = 5.seconds
    val initialDelay: FiniteDuration = 1.second
    val isEnabled: Boolean           = true

    val start = new CountDownLatch(1)

    def completeRun() = start.countDown()

    override def executeInLock(using ExecutionContext): Future[String] = {
      start.await
      service.call()
      Future.successful("done")
    }
  }

  trait Setup {
    val fakeService        = mock[FakeService]
    val mockLockRepository = mock[MongoLockRepository]

    val subject = new FakeServiceJob(fakeService, mockLockRepository)
  }

  "LockedScheduledJobSpec" should {

    "back off when Mutex cannot be acquired" in new Setup {
      when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(Some(Lock("", "", instant, instant))))
      when(mockLockRepository.releaseLock(*, *)).thenReturn(Future.successful(()))
      when(fakeService.call()).thenReturn("faked")

      // Start first copy of job (but not complete)
      subject.execute

      // Run second job which should notice first job is running
      val result2 = await(subject.execute)

      result2 shouldBe "Skipping execution: job running"

      verify(mockLockRepository).takeLock(eqTo("FakeServiceJob-lock"), *, *)
      subject.completeRun()
    }

    "back off when Mongo lock cannot be obtained" in new Setup {
      when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(None))

      val result = await(subject.execute)

      result shouldBe "FakeServiceJob did not run because repository was locked by another instance of the scheduler."
      verify(mockLockRepository).takeLock(eqTo("FakeServiceJob-lock"), *, *)
      verify(fakeService, never).call()
    }

    "execute in lock when Mongo lock can be obtained" in new Setup {
      when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(Some(Lock("", "", instant, instant))))
      when(mockLockRepository.releaseLock(*, *)).thenReturn(Future.successful(()))
      when(fakeService.call()).thenReturn("faked")

      val resultF = subject.execute
      subject.completeRun()
      val result  = await(resultF)

      result shouldBe "FakeServiceJob Job ran successfully."
      verify(mockLockRepository).takeLock(eqTo("FakeServiceJob-lock"), *, *)
      verify(mockLockRepository).releaseLock(eqTo("FakeServiceJob-lock"), *)
    }
  }
}
