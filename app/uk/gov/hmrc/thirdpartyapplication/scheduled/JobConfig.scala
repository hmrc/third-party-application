/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import play.api.Logger
import uk.gov.hmrc.lock.LockKeeper
import uk.gov.hmrc.play.scheduling.{ExclusiveScheduledJob, ScheduledJob}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

case class JobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean) {
  override def toString = s"JobConfig{initialDelay=$initialDelay interval=$interval enabled=$enabled}"
}

trait ScheduledMongoJob extends ExclusiveScheduledJob with ScheduledJobState {

  val lockKeeper: LockKeeper
  def isEnabled: Boolean

  def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful]

  override def executeInMutex(implicit ec: ExecutionContext): Future[Result] = {
    lockKeeper tryLock {
      runJob
    } map {
      case Some(_) => Result(s"$name Job ran successfully.")
      case _ => Result(s"$name did not run because repository was locked by another instance of the scheduler.")
    } recover {
      case failure: RunningOfJobFailed => {
        Logger.error("The execution of the job failed.", failure.wrappedCause)
        failure.asResult
      }
    }
  }
}

trait ScheduledJobState { e: ScheduledJob =>
  sealed trait RunningOfJobSuccessful

  case object RunningOfJobSuccessful extends RunningOfJobSuccessful

  case class RunningOfJobFailed(jobName: String, wrappedCause: Throwable) extends RuntimeException {
    def asResult = {
      Result(s"The execution of scheduled job $jobName failed with error '${wrappedCause.getMessage}'. " +
        s"The next execution of the job will do retry.")
    }
  }
}