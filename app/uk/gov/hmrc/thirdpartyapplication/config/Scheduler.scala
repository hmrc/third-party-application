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
import uk.gov.hmrc.play.scheduling.{ExclusiveScheduledJob, RunningOfScheduledJobs}
import uk.gov.hmrc.thirdpartyapplication.scheduled._

class SchedulerModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[Scheduler]).asEagerSingleton()
    bind(classOf[LoggerLike]).toInstance(Logger)
  }
}

@Singleton
class Scheduler @Inject()(upliftVerificationExpiryJobConfig: UpliftVerificationExpiryJobConfig,
                          upliftVerificationExpiryJob: UpliftVerificationExpiryJob,
                          metricsJobConfig: MetricsJobConfig,
                          metricsJob: MetricsJob,
                          bcryptPerformanceMeasureJob: BCryptPerformanceMeasureJob,
                          apiStorageConfig: ApiStorageConfig,
                          app: Application) extends RunningOfScheduledJobs {

  override val scheduledJobs: Seq[ExclusiveScheduledJob] = {
    val upliftJob = if (upliftVerificationExpiryJobConfig.enabled) Seq(upliftVerificationExpiryJob) else Seq.empty
    val mJob = if (metricsJobConfig.enabled) Seq(metricsJob) else Seq.empty
    val bcryptJob = Seq(bcryptPerformanceMeasureJob)

    // TODO : MetricsJob optional?
    upliftJob ++ mJob ++ bcryptJob
  }

  onStart(app)

}

case class SchedulerConfig(upliftVerificationExpiryJobConfigEnabled: Boolean, refreshSubscriptionsJobConfigEnabled: Boolean)
