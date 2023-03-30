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

import java.time.Clock
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{Cancellable, Scheduler}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.{ApplicationLifecycle, bind}
import play.api.test.Helpers.{await, defaultAwaitTimeout}

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.thirdpartyapplication.config.{ClockModule, SchedulerModule}

class RunningOfScheduledJobsSpec extends AnyWordSpec with Matchers with Eventually with MockitoSugar with GuiceOneAppPerTest with FixedClock {

  final override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule], classOf[ClockModule])
      .build()

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds)

  "When starting the app, the scheduled job runner" should {

    "schedule a configured job with the given interval and initialDuration" in new TestCase {
      object Captured {
        var initialDelay: FiniteDuration = _
        var interval: FiniteDuration     = _
      }

      val hasBeenScheduled = new CountDownLatch(1)

      private val testApp = fakeApplication()

      new RunningOfScheduledJobs {
        override lazy val ec: ExecutionContext                       = ExecutionContext.Implicits.global
        override lazy val applicationLifecycle: ApplicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]
        override lazy val scheduledJobs: Seq[ScheduledJob]           = Seq(testScheduledJob)
        override lazy val application: Application                   = testApp
        override lazy val scheduler: Scheduler                       = new StubbedScheduler {
          override def scheduleWithFixedDelay(initialDelay: FiniteDuration, interval: FiniteDuration)(runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
            Captured.initialDelay = initialDelay
            Captured.interval = interval
            hasBeenScheduled.countDown()
            new Cancellable {
              override def cancel(): Boolean    = true
              override def isCancelled: Boolean = false
            }
          }
        }
      }
      hasBeenScheduled.await(10, TimeUnit.SECONDS)
      Captured should have('initialDelay(testScheduledJob.initialDelay))
      Captured should have('interval(testScheduledJob.interval))

      await(testApp.stop())
    }

    "set up the scheduled job to run the execute method" in new TestCase {
      private val testApp = fakeApplication()

      var capturedRunnable: Runnable = _
      val hasBeenScheduled           = new CountDownLatch(1)

      override val testScheduledJob = new TestScheduledJob {
        var executed                                                        = false
        override def execute(implicit ec: ExecutionContext): Future[Result] = {
          executed = true
          Future.successful(this.Result("done"))
        }
        override def isExecuted: Boolean                                    = executed
      }

      new RunningOfScheduledJobs {
        override lazy val ec: ExecutionContext                       = ExecutionContext.Implicits.global
        override lazy val applicationLifecycle: ApplicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]
        override lazy val scheduler: Scheduler                       = new StubbedScheduler {
          override def scheduleWithFixedDelay(initialDelay: FiniteDuration, interval: FiniteDuration)(runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
            capturedRunnable = runnable
            hasBeenScheduled.countDown()
            new Cancellable {
              override def cancel(): Boolean    = true
              override def isCancelled: Boolean = false
            }
          }
        }

        override lazy val scheduledJobs: Seq[ScheduledJob] = Seq(testScheduledJob)
        override lazy val application: Application         = testApp
      }

      hasBeenScheduled.await(10, TimeUnit.SECONDS)
      testScheduledJob.isExecuted should be(false)
      capturedRunnable.run()
      testScheduledJob.isExecuted should be(true)

      await(testApp.stop())
    }
  }

  "When stopping the app, the scheduled job runner" should {
    "cancel all of the scheduled jobs" in new TestCase {
      private val testApp = fakeApplication()
      private val runner  = new RunningOfScheduledJobs {
        override lazy val ec: ExecutionContext                       = ExecutionContext.Implicits.global
        override lazy val applicationLifecycle: ApplicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]
        override lazy val scheduledJobs: Seq[ScheduledJob]           = Seq.empty
        override lazy val application: Application                   = testApp
      }
      runner.cancellables = Seq(new StubCancellable, new StubCancellable)

      every(runner.cancellables) should not be 'cancelled
      await(testApp.stop())
      every(runner.cancellables) should be('cancelled)
    }

    "block while a scheduled jobs are still running" in new TestCase {
      private val testApp                = fakeApplication()
      val stoppableJob: TestScheduledJob = new TestScheduledJob() {
        override def name: String = "StoppableJob"
      }
      new RunningOfScheduledJobs {
        override lazy val ec: ExecutionContext                       = ExecutionContext.Implicits.global
        override lazy val applicationLifecycle: ApplicationLifecycle = testApp.injector.instanceOf[ApplicationLifecycle]
        override lazy val scheduledJobs: Seq[ScheduledJob]           = Seq(stoppableJob)
        override lazy val application: Application                   = testApp
      }

      stoppableJob.isRunning = Future.successful(true)

      val deadline: Deadline = 5000.milliseconds.fromNow
      while (deadline.hasTimeLeft()) {
        /* Intentionally burning CPU cycles for fixed period */
      }

      val stopFuture: Future[_] = testApp.stop()
      stopFuture should not be 'completed

      stoppableJob.isRunning = Future.successful(false)
      eventually(timeout(Span(1, Minute))) { stopFuture should be('completed) }
    }
  }

  trait TestCase {

    class StubbedScheduler extends Scheduler {

      override def scheduleWithFixedDelay(initialDelay: FiniteDuration, delay: FiniteDuration)(runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
        new Cancellable {
          override def cancel(): Boolean    = true
          override def isCancelled: Boolean = false
        }

      def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = new Cancellable {
        override def cancel(): Boolean    = true
        override def isCancelled: Boolean = false
      }

      def maxFrequency: Double = 1

      def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = new Cancellable {
        override def cancel(): Boolean    = true
        override def isCancelled: Boolean = false
      }
    }

    class TestScheduledJob extends ScheduledJob {
      override lazy val initialDelay: FiniteDuration = 2.seconds
      override lazy val interval: FiniteDuration     = 3.seconds
      def name: String                               = "TestScheduledJob"
      def isExecuted: Boolean                        = true

      def execute(implicit ec: ExecutionContext): Future[Result] = Future.successful(Result("done"))
      var isRunning: Future[Boolean]                             = Future.successful(false)
    }

    val testScheduledJob = new TestScheduledJob

    class StubCancellable extends Cancellable {
      var isCancelled = false

      def cancel(): Boolean = {
        isCancelled = true
        isCancelled
      }
    }
  }
}
