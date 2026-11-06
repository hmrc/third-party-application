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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.concurrent.TimeUnit.*
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.{Duration, FiniteDuration}

import com.google.inject.Provider

import play.api.inject.Module
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.scheduling.ScheduledJobs
import uk.gov.hmrc.thirdpartyapplication.config.ConfigHelper
import uk.gov.hmrc.thirdpartyapplication.scheduled.*

class SchedulerModule extends Module with ApplicationLogger {

  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[UpliftVerificationExpiryJobConfig].toProvider[UpliftVerificationExpiryJobConfigProvider],
    bind[ProductionCredentialsRequestExpiryWarningJobConfig].toProvider[ProductionCredentialsRequestExpiryWarningJobConfigProvider],
    bind[ProductionCredentialsRequestExpiredJobConfig].toProvider[ProductionCredentialsRequestExpiredJobConfigProvider],
    bind[ResponsibleIndividualVerificationReminderJobConfig].toProvider[ResponsibleIndividualVerificationReminderJobConfigProvider],
    bind[ResponsibleIndividualVerificationRemovalJobConfig].toProvider[ResponsibleIndividualVerificationRemovalJobConfigProvider],
    bind[ResponsibleIndividualUpdateVerificationRemovalJobConfig].toProvider[ResponsibleIndividualUpdateVerificationRemovalJobConfigProvider],
    bind[ResponsibleIndividualVerificationSetDefaultTypeJobConfig].toProvider[ResponsibleIndividualVerificationSetDefaultTypeJobConfigProvider],
    bind[TermsOfUseInvitationReminderJobConfig].toProvider[TermsOfUseInvitationReminderJobConfigProvider],
    bind[TermsOfUseInvitationOverdueJobConfig].toProvider[TermsOfUseInvitationOverdueJobConfigProvider],
    bind[ScheduledJobs].toProvider[ScheduledJobsProvider],
    bind[MetricsScheduler].toSelf.eagerly()
    // bind(classOf[LoggerLike]).toInstance(logger)
  )
}

private class ScheduledJobsProvider @Inject() (
    upliftVerificationExpiryJob: UpliftVerificationExpiryJob,
    bcryptPerformanceMeasureJob: BCryptPerformanceMeasureJob,
    productionCredentialsRequestExpiryWarningJob: ProductionCredentialsRequestExpiryWarningJob,
    productionCredentialsRequestExpiredJob: ProductionCredentialsRequestExpiredJob,
    responsibleIndividualVerificationReminderJob: ResponsibleIndividualVerificationReminderJob,
    responsibleIndividualVerificationRemovalJob: ResponsibleIndividualVerificationRemovalJob,
    responsibleIndividualVerificationSetDefaultTypeJob: ResponsibleIndividualVerificationSetDefaultTypeJob,
    responsibleIndividualUpdateVerificationRemovalJob: ResponsibleIndividualUpdateVerificationRemovalJob,
    termsOfUseInvitationReminderJob: TermsOfUseInvitationReminderJob,
    termsOfUseInvitationOverdueJob: TermsOfUseInvitationOverdueJob
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
      .filter(_.isEnabled)
  )
}

@Singleton
class UpliftVerificationExpiryJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[UpliftVerificationExpiryJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "upliftVerificationExpiryJob")
      .getOrElse(JobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true))

    val validity: FiniteDuration = configuration.getOptional[FiniteDuration]("upliftVerificationValidity")
      .getOrElse(Duration(90, DAYS))

    UpliftVerificationExpiryJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled, validity)
  }
}

@Singleton
class ProductionCredentialsRequestExpiryWarningJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ProductionCredentialsRequestExpiryWarningJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "productionCredentialsRequestExpiryWarningJob")
      .getOrElse(JobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true))

    val warningInterval: FiniteDuration = configuration.getOptional[FiniteDuration]("productionCredentialsRequestExpiryWarningJob.warningInterval")
      .getOrElse(Duration(150, DAYS))

    ProductionCredentialsRequestExpiryWarningJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled, warningInterval)
  }
}

@Singleton
class ProductionCredentialsRequestExpiredJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ProductionCredentialsRequestExpiredJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "productionCredentialsRequestExpiredJob")
      .getOrElse(JobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true))

    val deleteInterval: FiniteDuration = configuration.getOptional[FiniteDuration]("productionCredentialsRequestExpiredJob.deleteInterval")
      .getOrElse(Duration(183, DAYS))

    ProductionCredentialsRequestExpiredJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled, deleteInterval)
  }
}

@Singleton
class ResponsibleIndividualVerificationReminderJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ResponsibleIndividualVerificationReminderJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "responsibleIndividualVerificationReminderJob")
      .getOrElse(JobConfig(FiniteDuration(2, MINUTES), FiniteDuration(1, HOURS), enabled = true))

    val reminderInterval: FiniteDuration = configuration.getOptional[FiniteDuration]("responsibleIndividualVerificationReminderJob.reminderInterval")
      .getOrElse(Duration(10, DAYS))

    ResponsibleIndividualVerificationReminderJobConfig(jobConfig.initialDelay, jobConfig.interval, reminderInterval, jobConfig.enabled)
  }
}

@Singleton
class ResponsibleIndividualVerificationRemovalJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ResponsibleIndividualVerificationRemovalJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "responsibleIndividualVerificationRemovalJob")
      .getOrElse(JobConfig(FiniteDuration(2, MINUTES), FiniteDuration(1, HOURS), enabled = true))

    val removalInterval: FiniteDuration = configuration.getOptional[FiniteDuration]("responsibleIndividualVerificationRemovalJob.removalInterval")
      .getOrElse(Duration(10, DAYS))

    ResponsibleIndividualVerificationRemovalJobConfig(jobConfig.initialDelay, jobConfig.interval, removalInterval, jobConfig.enabled)
  }
}

@Singleton
class ResponsibleIndividualUpdateVerificationRemovalJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ResponsibleIndividualUpdateVerificationRemovalJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "responsibleIndividualUpdateVerificationRemovalJob")
      .getOrElse(JobConfig(FiniteDuration(2, MINUTES), FiniteDuration(1, HOURS), enabled = true))

    val removalInterval: FiniteDuration = configuration.getOptional[FiniteDuration]("responsibleIndividualUpdateVerificationRemovalJob.removalInterval")
      .getOrElse(Duration(10, DAYS))

    ResponsibleIndividualUpdateVerificationRemovalJobConfig(jobConfig.initialDelay, jobConfig.interval, removalInterval, jobConfig.enabled)
  }
}

@Singleton
class ResponsibleIndividualVerificationSetDefaultTypeJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ResponsibleIndividualVerificationSetDefaultTypeJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "responsibleIndividualVerificationSetDefaultTypeJob")
      .getOrElse(JobConfig(FiniteDuration(2, MINUTES), FiniteDuration(30, DAYS), enabled = true))

    ResponsibleIndividualVerificationSetDefaultTypeJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled)
  }
}

@Singleton
class TermsOfUseInvitationReminderJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[TermsOfUseInvitationReminderJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "termsOfUseInvitationReminderJob")
      .getOrElse(JobConfig(FiniteDuration(6, MINUTES), FiniteDuration(8, HOURS), enabled = true))

    val reminderInterval: FiniteDuration = configuration.getOptional[FiniteDuration]("termsOfUseInvitationReminderJob.reminderInterval")
      .getOrElse(Duration(30, DAYS))

    TermsOfUseInvitationReminderJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled, reminderInterval)
  }
}

@Singleton
class TermsOfUseInvitationOverdueJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[TermsOfUseInvitationOverdueJobConfig] {

  override def get() = {
    val jobConfig = ConfigHelper.getJobConfig(configuration, "termsOfUseInvitationOverdueJob")
      .getOrElse(JobConfig(FiniteDuration(8, MINUTES), FiniteDuration(8, HOURS), enabled = true))

    TermsOfUseInvitationOverdueJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled)
  }
}
