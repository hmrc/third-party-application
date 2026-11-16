/*
 * Copyright 2026 HM Revenue & Customs
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
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}

class DummyExclusiveScheduledJob(
    val initialDelay: FiniteDuration = 1.seconds,
    jobCompleter: Promise[String] = Promise.successful("Done")
  ) extends ExclusiveScheduledJob {

  override val interval: FiniteDuration = 1.hour
  def name: String                      = "DummyExclusiveScheduledJob1"

  val isEnabled = true

  private val startedMarker   = CountDownLatch(1)
  private val completedMarker = CountDownLatch(1)

  def awaitStarted(timeout: FiniteDuration): Boolean = {
    startedMarker.await(timeout.length, timeout.unit)
  }

  def awaitCompleted(timeout: FiniteDuration): Boolean = {
    completedMarker.await(timeout.length, timeout.unit)
  }

  override def executeInMutex(using ExecutionContext): Future[String] = {
    startedMarker.countDown()
    val future = jobCompleter.future
    future.onComplete { case _ =>
      completedMarker.countDown()
    }
    future
  }
}
