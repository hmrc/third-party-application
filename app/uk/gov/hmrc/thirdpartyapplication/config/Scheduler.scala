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

package uk.gov.hmrc.thirdpartyapplication.config

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import com.google.inject.AbstractModule

import play.api.inject.ApplicationLifecycle
import play.api.{Application, LoggerLike}

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.scheduling.{ExclusiveScheduledJob, RunningOfScheduledJobs}
import uk.gov.hmrc.thirdpartyapplication.scheduled._

class SchedulerModule extends AbstractModule with ApplicationLogger {

  override def configure(): Unit = {
    bind(classOf[Scheduler]).asEagerSingleton()
    bind(classOf[MetricsScheduler]).asEagerSingleton()
    bind(classOf[LoggerLike]).toInstance(logger)
  }
}

@Singleton
class Scheduler @Inject() (
    upliftVerificationExpiryJob: UpliftVerificationExpiryJob,
    bcryptPerformanceMeasureJob: BCryptPerformanceMeasureJob,
    resetLastAccessDateJob: ResetLastAccessDateJob,
    productionCredentialsRequestExpiryWarningJob: ProductionCredentialsRequestExpiryWarningJob,
    productionCredentialsRequestExpiredJob: ProductionCredentialsRequestExpiredJob,
    responsibleIndividualVerificationReminderJob: ResponsibleIndividualVerificationReminderJob,
    responsibleIndividualVerificationRemovalJob: ResponsibleIndividualVerificationRemovalJob,
    responsibleIndividualVerificationSetDefaultTypeJob: ResponsibleIndividualVerificationSetDefaultTypeJob,
    responsibleIndividualUpdateVerificationRemovalJob: ResponsibleIndividualUpdateVerificationRemovalJob,
    termsOfUseInvitationReminderJob: TermsOfUseInvitationReminderJob,
    override val applicationLifecycle: ApplicationLifecycle,
    override val application: Application
  )(implicit val ec: ExecutionContext
  ) extends RunningOfScheduledJobs {

  override lazy val scheduledJobs: Seq[ExclusiveScheduledJob] = {
    Seq(
      upliftVerificationExpiryJob,
      resetLastAccessDateJob,
      productionCredentialsRequestExpiryWarningJob,
      productionCredentialsRequestExpiredJob,
      responsibleIndividualVerificationReminderJob,
      responsibleIndividualVerificationRemovalJob,
      responsibleIndividualUpdateVerificationRemovalJob,
      responsibleIndividualVerificationSetDefaultTypeJob,
      termsOfUseInvitationReminderJob
    )
      .filter(_.isEnabled) ++ Seq(bcryptPerformanceMeasureJob)
  }
}

case class SchedulerConfig(upliftVerificationExpiryJobConfigEnabled: Boolean, refreshSubscriptionsJobConfigEnabled: Boolean)
