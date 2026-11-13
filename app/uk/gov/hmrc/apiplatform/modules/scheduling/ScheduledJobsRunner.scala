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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import org.apache.commons.lang3.time.StopWatch
import org.apache.pekko.actor.{Cancellable, Scheduler}

import play.api.inject.ApplicationLifecycle
import play.api.{Application, Logging}

case class ScheduledJobs(allJobs: List[ExclusiveScheduledJob])

@Singleton
class ScheduledJobsRunner @Inject() (
    application: Application,
    applicationLifecycle: ApplicationLifecycle,
    scheduledJobs: ScheduledJobs
  )(using ExecutionContext
  ) extends Logging {

  val scheduler: Scheduler = application.actorSystem.scheduler

  val cancellables: Seq[Cancellable] =
    scheduledJobs.allJobs.map { job =>
      scheduler.scheduleWithFixedDelay(job.initialDelay, job.interval)(new Runnable() {
        override def run(): Unit = {
          val stopWatch = new StopWatch
          stopWatch.start()
          logger.debug(s"Executing job ${job.name}")

          job.execute.onComplete {
            case Success(message: String) =>
              stopWatch.stop()
              logger.debug(s"Completed job ${job.name} in $stopWatch: $message")
            case Failure(throwable)       =>
              stopWatch.stop()

              logger.error(s"Exception running job ${job.name} after $stopWatch", throwable)
          }
        }
      })
    }

  applicationLifecycle.addStopHook { () =>
    logger.info(s"Cancelling all scheduled jobs.")
    cancellables.foreach(_.cancel())

    scheduledJobs.allJobs.foreach { job =>
      logger.info(s"Checking if job ${job.configKey} is running")
      while (job.isRunning) {
        logger.warn(s"Waiting for job ${job.configKey} to finish")
        Thread.sleep(1000)
      }
      logger.warn(s"Job ${job.configKey} is finished")
    }

    Future.successful(())
  }

}
