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

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

trait ExclusiveLockedScheduledJob extends ExclusiveScheduledJob with ApplicationLogger {

  def executeInLock(using ExecutionContext): Future[String]

  def mongoLockRepository: MongoLockRepository

  val lockSafetyMarginExcess: Duration = 10.minutes

  // Lock for 10 minutes (or other) longer than the interval to allow for retries and timeouts
  private lazy val lockService: LockService = LockService(mongoLockRepository, lockId = s"$name-lock", ttl = interval + lockSafetyMarginExcess)

  final def executeInMutex(using ExecutionContext): Future[String] =
    lockService.withLock {
      executeInLock
    } map {
      case Some(_) => s"$name Job ran successfully."
      case _       => s"$name did not run because repository was locked by another instance of the scheduler."
    } recover {
      case failure: Exception =>
        logger.error("The execution of the job failed.", failure)

        s"The execution of scheduled job $name failed with error '${failure.getMessage}'. The next execution of the job will do retry."
    }
}
