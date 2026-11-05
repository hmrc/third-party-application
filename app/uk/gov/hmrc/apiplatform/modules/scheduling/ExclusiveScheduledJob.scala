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

import java.util.concurrent.Semaphore
import scala.concurrent.{ExecutionContext, Future}

trait ExclusiveScheduledJob extends ScheduledJob {

  def executeInMutex(using ExecutionContext): Future[String]

  final def execute(using ExecutionContext): Future[String] =
    if (mutex.tryAcquire()) {
      val result = executeInMutex
      result.onComplete(_ => mutex.release())
      result
    } else Future.successful("Skipping execution: job running")

  def isRunning: Boolean = mutex.availablePermits() == 0

  final private val mutex = new Semaphore(1)
}