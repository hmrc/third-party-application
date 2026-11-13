/*
 * Copyright 2024 HM Revenue & Customs
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

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.{FiniteDuration, *}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class ExclusiveScheduledJobSpec extends AsyncHmrcSpec with Matchers with ScalaFutures with Eventually {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  class SimpleJob extends ExclusiveScheduledJob {

    val start = new CountDownLatch(1)

    def completeRun() = start.countDown()

    val executionCount = new AtomicInteger(0)

    def executions: Int = executionCount.get()

    def isEnabled = true

    override def executeInMutex(using ExecutionContext): Future[String] =
      Future {
        start.await(1, TimeUnit.MINUTES)
        executionCount.incrementAndGet().toString
      }

    override val name = "simpleJob"

    override val initialDelay = 1.minute

    override val interval = 1.minute
  }

  "ExclusiveScheduledJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "let job run in sequence" in {
      val job = new SimpleJob
      job.completeRun()
      job.execute.futureValue shouldBe "1"
      job.execute.futureValue shouldBe "2"
    }

    "not allow job to run in parallel" in {
      val job = new SimpleJob

      val pausedExecution = job.execute
      pausedExecution.isCompleted shouldBe false
      job.isRunning shouldBe true
      job.execute.futureValue shouldBe "Skipping execution: job running"
      job.isRunning shouldBe true

      job.completeRun()
      pausedExecution.futureValue shouldBe "1"
      eventually {
        job.isRunning shouldBe false
      }
    }

    "tolerate exceptions in execution ensuring job is marked as no longer running" in {
      val job = new SimpleJob() {
        override def executeInMutex(using ExecutionContext): Future[String] = Future { throw new RuntimeException }
      }

      // Because futureValue would rethrow in this context wrap in Try
      Try(job.execute.futureValue)

      job.isRunning shouldBe false
    }
  }
}
