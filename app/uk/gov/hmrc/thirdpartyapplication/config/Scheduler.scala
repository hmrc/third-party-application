/*
 * Copyright 2019 HM Revenue & Customs
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
import play.api.Application
import uk.gov.hmrc.play.scheduling.{ExclusiveScheduledJob, RunningOfScheduledJobs}
import uk.gov.hmrc.thirdpartyapplication.scheduled.{SetLastAccessedDateJobConfig, _}

class SchedulerModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[Scheduler]).asEagerSingleton()
  }
}

@Singleton
class Scheduler @Inject()(upliftVerificationExpiryJobConfig: UpliftVerificationExpiryJobConfig,
                          upliftVerificationExpiryJob: UpliftVerificationExpiryJob,
                          refreshSubscriptionsJobConfig: RefreshSubscriptionsJobConfig,
                          refreshSubscriptionsScheduledJob: RefreshSubscriptionsScheduledJob,
                          setLastAccessedDateJobConfig: SetLastAccessedDateJobConfig,
                          setLastAccessedDateJob: SetLastAccessedDateJob,
                          app: Application) extends RunningOfScheduledJobs {

  override val scheduledJobs: Seq[ExclusiveScheduledJob] = {

    val upliftJob: Seq[ExclusiveScheduledJob] = if (upliftVerificationExpiryJobConfig.enabled) {
      Seq(upliftVerificationExpiryJob)
    } else {
      Seq.empty
    }

    val refreshJob = if (refreshSubscriptionsJobConfig.enabled) {
      Seq(refreshSubscriptionsScheduledJob)
    } else {
      Seq.empty
    }

    val accessDateJob = if (setLastAccessedDateJobConfig.enabled) {
      Seq(setLastAccessedDateJob)
    } else {
      Seq.empty
    }

    upliftJob ++ refreshJob ++ accessDateJob

  }

  onStart(app)

}

case class SchedulerConfig(upliftVerificationExpiryJobConfigEnabled: Boolean, refreshSubscriptionsJobConfigEnabled: Boolean)
