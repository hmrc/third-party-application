/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.config

import com.google.inject.AbstractModule
import javax.inject.{Inject, Singleton}
import play.api.{Application, Logger, LoggerLike}
import uk.gov.hmrc.play.scheduling.{ExclusiveScheduledJob, RunningOfScheduledJobs, ScheduledJob}
import uk.gov.hmrc.thirdpartyapplication.scheduled._
import play.api.inject.ApplicationLifecycle
import scala.concurrent.ExecutionContext

class SchedulerModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[Scheduler]).asEagerSingleton()
    bind(classOf[LoggerLike]).toInstance(Logger)
  }
}

@Singleton
class Scheduler @Inject()(upliftVerificationExpiryJob: UpliftVerificationExpiryJob,
                          metricsJob: MetricsJob,
                          bcryptPerformanceMeasureJob: BCryptPerformanceMeasureJob,
                          renameContextJob: RenameContextJob,
                          override val applicationLifecycle: ApplicationLifecycle,
                          override val application: Application)
                          (implicit val ec: ExecutionContext)
                          extends RunningOfScheduledJobs {

  override lazy val scheduledJobs: Seq[ExclusiveScheduledJob] =  {
    // TODO : MetricsJob optional?
    Seq(upliftVerificationExpiryJob, metricsJob, renameContextJob).filter(_.isEnabled) ++ Seq(bcryptPerformanceMeasureJob)
  }
}

case class SchedulerConfig(upliftVerificationExpiryJobConfigEnabled: Boolean, refreshSubscriptionsJobConfigEnabled: Boolean)
