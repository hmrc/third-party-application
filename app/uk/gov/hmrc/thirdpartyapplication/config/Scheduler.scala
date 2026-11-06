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

import com.google.inject.Provider
import play.api.{Configuration, Environment}

import play.api.inject.{ApplicationLifecycle, Module}
import play.api.{Application, LoggerLike}

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.scheduling.ExclusiveScheduledJob
import uk.gov.hmrc.thirdpartyapplication.scheduled.*
import uk.gov.hmrc.apiplatform.modules.scheduling.ScheduledJobs

class SchedulerModule extends Module with ApplicationLogger {
  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[ScheduledJobs].toProvider[ScheduledJobsProvider]
    // bind(classOf[Scheduler]).asEagerSingleton()
    // bind(classOf[MetricsScheduler]).asEagerSingleton()
    // bind(classOf[LoggerLike]).toInstance(logger)
  )
}

// class Scheduler @Inject() (
//     upliftVerificationExpiryJob: UpliftVerificationExpiryJob,
//     bcryptPerformanceMeasureJob: BCryptPerformanceMeasureJob,
//     productionCredentialsRequestExpiryWarningJob: ProductionCredentialsRequestExpiryWarningJob,
//     productionCredentialsRequestExpiredJob: ProductionCredentialsRequestExpiredJob,
//     responsibleIndividualVerificationReminderJob: ResponsibleIndividualVerificationReminderJob,
//     responsibleIndividualVerificationRemovalJob: ResponsibleIndividualVerificationRemovalJob,
//     responsibleIndividualVerificationSetDefaultTypeJob: ResponsibleIndividualVerificationSetDefaultTypeJob,
//     responsibleIndividualUpdateVerificationRemovalJob: ResponsibleIndividualUpdateVerificationRemovalJob,
//     termsOfUseInvitationReminderJob: TermsOfUseInvitationReminderJob,
//     termsOfUseInvitationOverdueJob: TermsOfUseInvitationOverdueJob,
//     override val applicationLifecycle: ApplicationLifecycle,
//     override val application: Application
//   )(implicit val ec: ExecutionContext
//   ) extends RunningOfScheduledJobs {

//   override val scheduledJobs: Seq[ExclusiveScheduledJob] = {
//     Seq(
//       upliftVerificationExpiryJob,
//       productionCredentialsRequestExpiryWarningJob,
//       productionCredentialsRequestExpiredJob,
//       responsibleIndividualVerificationReminderJob,
//       responsibleIndividualVerificationRemovalJob,
//       responsibleIndividualUpdateVerificationRemovalJob,
//       responsibleIndividualVerificationSetDefaultTypeJob,
//       termsOfUseInvitationReminderJob,
//       termsOfUseInvitationOverdueJob
//     )
//       .filter(_.isEnabled) ++ Seq(bcryptPerformanceMeasureJob)
//   }
// }

// case class SchedulerConfig(upliftVerificationExpiryJobConfigEnabled: Boolean, refreshSubscriptionsJobConfigEnabled: Boolean)

@Singleton
class ScheduledJobsProvider @Inject() (
  upliftVerificationExpiryJob: UpliftVerificationExpiryJob,
  bcryptPerformanceMeasureJob: BCryptPerformanceMeasureJob,
  productionCredentialsRequestExpiryWarningJob: ProductionCredentialsRequestExpiryWarningJob,
  productionCredentialsRequestExpiredJob: ProductionCredentialsRequestExpiredJob,
  responsibleIndividualVerificationReminderJob: ResponsibleIndividualVerificationReminderJob,
  responsibleIndividualVerificationRemovalJob: ResponsibleIndividualVerificationRemovalJob,
  responsibleIndividualVerificationSetDefaultTypeJob: ResponsibleIndividualVerificationSetDefaultTypeJob,
  responsibleIndividualUpdateVerificationRemovalJob: ResponsibleIndividualUpdateVerificationRemovalJob,
  termsOfUseInvitationReminderJob: TermsOfUseInvitationReminderJob,
  termsOfUseInvitationOverdueJob: TermsOfUseInvitationOverdueJob,
) extends Provider[ScheduledJobs] {
  override def get(): ScheduledJobs = ScheduledJobs(
    List(
      upliftVerificationExpiryJob,
      bcryptPerformanceMeasureJob,
      productionCredentialsRequestExpiryWarningJob,
      productionCredentialsRequestExpiredJob,
      responsibleIndividualVerificationReminderJob,
      responsibleIndividualVerificationRemovalJob,
      responsibleIndividualVerificationSetDefaultTypeJob,
      responsibleIndividualUpdateVerificationRemovalJob,
      termsOfUseInvitationReminderJob,
      termsOfUseInvitationOverdueJob
    )
    .filter(_.isEnabled))
}
