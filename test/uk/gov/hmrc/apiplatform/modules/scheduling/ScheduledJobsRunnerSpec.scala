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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Success

import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minute, Span}
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import play.api.Application
import play.api.inject.ApplicationLifecycle
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.{await, defaultAwaitTimeout}

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec

class ScheduledJobsRunnerSpec extends HmrcSpec with ScalaFutures with GuiceOneAppPerTest with BeforeAndAfterEach {

  override def fakeApplication() =
    new GuiceApplicationBuilder()
      .configure(
        "metrics.jvm"     -> false,
        "metrics.enabled" -> false
      )
      .build()

  "ScheduledJobsRunner" should {
    "not execute jobs during the initial delay" in {
      val testApp              = fakeApplication()
      val applicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]
      val testJob              = new DummyExclusiveScheduledJob(5.seconds)

      ScheduledJobsRunner(testApp, applicationLifecycle, ScheduledJobs(List(testJob)))

      // it won't start yet - see CDL await
      withClue("testJob has started too soon") { testJob.awaitStarted(4.seconds) shouldBe false }

      // it should have started now
      withClue("testJob has not started") { testJob.awaitStarted(5.seconds) shouldBe true }

      await(testApp.stop())
    }

    "execute jobs after the initial delay" in {
      val testApp              = fakeApplication()
      val applicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]

      val testJob1 = new DummyExclusiveScheduledJob(1.seconds)
      val testJob2 = new DummyExclusiveScheduledJob(1.seconds)

      ScheduledJobsRunner(testApp, applicationLifecycle, ScheduledJobs(List(testJob1, testJob2)))

      // Both jobs should be executed quickly
      withClue("testJob1 has not started") { testJob1.awaitStarted(5.seconds) shouldBe true }
      withClue("testJob2 has not started") { testJob2.awaitStarted(5.seconds) shouldBe true }

      await(testApp.stop())
    }

    "cancel all of the scheduled jobs when stopping the app" in {
      val testApp              = fakeApplication()
      val applicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]

      val testJob1 = new DummyExclusiveScheduledJob()
      val testJob2 = new DummyExclusiveScheduledJob()

      val runner = ScheduledJobsRunner(testApp, applicationLifecycle, ScheduledJobs(List(testJob1, testJob2)))

      every(runner.cancellables) should not be Symbol("cancelled")

      val stopFuture = testApp.stop()
      eventually(timeout(Span(1, Minute))) { stopFuture shouldBe Symbol("completed") }

      every(runner.cancellables) shouldBe Symbol("cancelled")
    }

    "demonstrate job completion can be delayed in DummyExclusiveScheduledJob before next test" in {
      val testApp              = fakeApplication()
      val applicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]

      val jobCompleter = Promise[String]
      val testJob      = new DummyExclusiveScheduledJob(1.seconds, jobCompleter)

      ScheduledJobsRunner(testApp, applicationLifecycle, ScheduledJobs(List(testJob)))

      withClue("testJob has started too soon") { testJob.awaitCompleted(5.seconds) shouldBe false }
      jobCompleter.complete(Success("Done"))
      withClue("testJob has not started") { testJob.awaitCompleted(1.seconds) shouldBe true }

      await(testApp.stop())
    }

    "block shutdown while scheduled jobs are still running" in {
      val testApp              = fakeApplication()
      val applicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]

      val jobCompleter = Promise[String]
      val testJob      = new DummyExclusiveScheduledJob(1.seconds, jobCompleter)

      val runner = ScheduledJobsRunner(testApp, applicationLifecycle, ScheduledJobs(List(testJob)))

      // Ensure we are running execute block
      withClue("testJob has not started") { testJob.awaitStarted(5.seconds) shouldBe true }

      val stopFuture = testApp.stop()

      // When we stop the app it should wait for the runnning job to complete
      stopFuture should not be Symbol("completed")

      // Complete the running scheduled job
      jobCompleter.complete(Success("Done"))

      // Check everything is shutdown
      eventually(timeout(Span(1, Minute))) { stopFuture shouldBe Symbol("completed") }
      every(runner.cancellables) shouldBe Symbol("cancelled")
    }
  }
}
