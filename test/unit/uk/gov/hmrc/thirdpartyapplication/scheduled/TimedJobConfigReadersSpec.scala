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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigException.{BadValue, Missing}
import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import org.joda.time.LocalTime
import org.mockito.ArgumentMatchersSugar
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{MustMatchers, WordSpec}
import uk.gov.hmrc.thirdpartyapplication.scheduled.{ApplicationToBeDeletedNotificationsConfig, DeleteUnusedApplicationsConfig, TimedJobConfig, TimedJobConfigReaders}

import scala.concurrent.duration.FiniteDuration

class TimedJobConfigReadersSpec extends WordSpec with MockitoSugar with ArgumentMatchersSugar with MustMatchers {

  "localTimeReader" should {
    val timePath: String = "jobConfig.time"

    def config(timeAsString: String) = ConfigFactory.parseString(
      s"""
         | jobConfig {
         |  time: "$timeAsString"
         | }
         |""".stripMargin)

    "correctly parse a valid time" in new TimedJobConfigReaders {
      val parsedLocalTime: LocalTime = config("00:30").as[LocalTime](timePath)

      parsedLocalTime must equal (new LocalTime(0, 30)) // scalastyle:off magic.number
    }

    "throw a BadValue exception if time cannot be parsed" in new TimedJobConfigReaders {
      assertThrows[BadValue] {
        config("24:30").as[LocalTime](timePath)
      }
    }
  }

  "timedJobConfigReader" should {
    val jobConfigPath: String = "TestScheduledJob"
    def fullConfiguration(startTime: String, executionInterval: String, enabled: Boolean): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  startTime = "$startTime",
           |  executionInterval = $executionInterval,
           |  enabled = $enabled
           | }
           |""".stripMargin)

    def noStartTimeConfig(executionInterval: String, enabled: Boolean): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  executionInterval = $executionInterval,
           |  enabled = $enabled
           | }
           |""".stripMargin)

    def noExecutionIntervalConfig(startTime: String, enabled: Boolean): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  startTime = "$startTime",
           |  enabled = $enabled
           | }
           |""".stripMargin)

    def noEnabledFlagConfig(startTime: String, executionInterval: String): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  startTime = "$startTime",
           |  executionInterval = $executionInterval
           | }
           |""".stripMargin)

    "correctly create a TimedJobConfig object when all values are populated" in new TimedJobConfigReaders {
      val config: Config = fullConfiguration("13:30", "1d", enabled = true)

      val parsedConfig: TimedJobConfig = config.as[TimedJobConfig](jobConfigPath)

      parsedConfig.startTime.isDefined must be (true)
      parsedConfig.startTime.get.startTime must equal (new LocalTime(13, 30))
      parsedConfig.executionInterval.interval must equal (new FiniteDuration(1, TimeUnit.DAYS))
      parsedConfig.enabled must be (true)
    }

    "correctly create a TimedJobConfig object when no startTime is specified" in new TimedJobConfigReaders {
      val config: Config = noStartTimeConfig("1d", enabled = true)

      val parsedConfig: TimedJobConfig = config.as[TimedJobConfig](jobConfigPath)

      parsedConfig.startTime.isDefined must be (false)
      parsedConfig.executionInterval.interval must equal (new FiniteDuration(1, TimeUnit.DAYS))
      parsedConfig.enabled must be (true)
    }

    "default enabled to false when not specified" in new TimedJobConfigReaders {
      val config: Config = noEnabledFlagConfig("17:55", "2d")

      val parsedConfig: TimedJobConfig = config.as[TimedJobConfig](jobConfigPath)

      parsedConfig.startTime.isDefined must be (true)
      parsedConfig.startTime.get.startTime must equal (new LocalTime(17, 55))
      parsedConfig.executionInterval.interval must equal (new FiniteDuration(2, TimeUnit.DAYS))
      parsedConfig.enabled must be (false)
    }

    "throw a Missing exception if executionInterval is not specified" in new TimedJobConfigReaders {
      val config: Config = noExecutionIntervalConfig("02:10", enabled = true)

      assertThrows[Missing] {
        config.as[TimedJobConfig](jobConfigPath)
      }
    }
  }

  "deleteUnusedApplicationsConfigReader" should {
    val jobConfigPath: String = "DeleteUnusedApplications"
    def fullConfiguration(cutoff: String, dryRun: Boolean): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  startTime = "10:00",
           |  executionInterval = "1d",
           |  enabled = true,
           |  deleteApplicationsIfUnusedFor = $cutoff,
           |  dryRun = $dryRun
           | }
           |""".stripMargin)

    def noDryRunConfig(cutoff: String): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  deleteApplicationsIfUnusedFor = $cutoff
           | }
           |""".stripMargin)

    def noCutoffConfig(dryRun: Boolean): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  dryRun = $dryRun
           | }
           |""".stripMargin)

    "correctly create a DeleteUnusedApplicationsConfig object when all values are populated" in new TimedJobConfigReaders {
      val config: Config = fullConfiguration("365d", dryRun = false)

      val parsedConfig: DeleteUnusedApplicationsConfig = config.as[DeleteUnusedApplicationsConfig](jobConfigPath)

      parsedConfig.deleteApplicationsIfUnusedFor must be (FiniteDuration(365, TimeUnit.DAYS))
      parsedConfig.dryRun must be (false)
    }

    "default dryRun to true if not specified" in new TimedJobConfigReaders {
      val config: Config = noDryRunConfig("180d")

      val parsedConfig: DeleteUnusedApplicationsConfig = config.as[DeleteUnusedApplicationsConfig](jobConfigPath)

      parsedConfig.deleteApplicationsIfUnusedFor must be (FiniteDuration(180, TimeUnit.DAYS))
      parsedConfig.dryRun must be (true)
    }

    "throw a Missing exception if cutoff is not specified" in new TimedJobConfigReaders {
      val config: Config = noCutoffConfig(dryRun = false)

      assertThrows[Missing] {
        config.as[DeleteUnusedApplicationsConfig](jobConfigPath)
      }
    }
  }

  "applicationToBeDeletedNotificationsConfigReader" should {
    val jobConfigPath: String = "ApplicationToBeDeletedNotifications"
    def fullConfiguration(notificationCutoff: String, emailServiceURL: String, emailTemplateId: String, environmentName: String,  dryRun: Boolean): Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  startTime = "10:00",
           |  executionInterval = "1d",
           |  enabled = true,
           |  sendNotificationsInAdvance = $notificationCutoff,
           |  emailServiceURL = "$emailServiceURL",
           |  emailTemplateId = $emailTemplateId,
           |  environmentName = $environmentName,
           |  dryRun = $dryRun
           | }
           |""".stripMargin)

    def noDryRunConfig: Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  sendNotificationsInAdvance = 30d,
           |  emailServiceURL = "http://email.service",
           |  emailTemplateId = "apiApplicationToBeDeletedNotification",
           |  environmentName = "Test Application"
           | }
           |""".stripMargin)

    def noEmailServiceURLConfig: Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  sendNotificationsInAdvance = 30d,
           |  emailTemplateId = apiApplicationToBeDeletedNotification,
           |  environmentName = "Test Application",
           |  dryRun = false
           | }
           |""".stripMargin)

    def noEmailTemplateIdConfig: Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  sendNotificationsInAdvance = 30d,
           |  emailServiceURL = "http://email.service",
           |  environmentName = "Test Application",
           |  dryRun = false
           | }
           |""".stripMargin)

    def noEnvironmentNameConfig: Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  sendNotificationsInAdvance = 30d,
           |  emailServiceURL = "http://email.service",
           |  emailTemplateId = apiApplicationToBeDeletedNotification,
           |  dryRun = false
           | }
           |""".stripMargin)

    def noNotificationCutoffConfig: Config =
      ConfigFactory.parseString(
        s"""
           | $jobConfigPath {
           |  emailServiceURL = "http://email.service",
           |  emailTemplateId = apiApplicationToBeDeletedNotification,
           |  environmentName = "Test Application",
           |  dryRun = false
           | }
           |""".stripMargin)

    "correctly create an DeleteUnusedApplicationsConfig object when all values are populated" in new TimedJobConfigReaders {
      val config: Config = fullConfiguration("30d", "http://email.service", "apiApplicationToBeDeletedNotification", "Test Environment", dryRun = false)

      val parsedConfig: ApplicationToBeDeletedNotificationsConfig = config.as[ApplicationToBeDeletedNotificationsConfig](jobConfigPath)

      parsedConfig.sendNotificationsInAdvance must be (FiniteDuration(30, TimeUnit.DAYS))
      parsedConfig.emailServiceURL must be ("http://email.service")
      parsedConfig.emailTemplateId must be ("apiApplicationToBeDeletedNotification")
      parsedConfig.environmentName must be ("Test Environment")
      parsedConfig.dryRun must be (false)
    }

    "default dryRun to true if not specified" in new TimedJobConfigReaders {
      val parsedConfig: ApplicationToBeDeletedNotificationsConfig = noDryRunConfig.as[ApplicationToBeDeletedNotificationsConfig](jobConfigPath)

      parsedConfig.dryRun must be (true)
    }

    "throw a Missing exception if emailServiceURL is not specified" in new TimedJobConfigReaders {
      assertThrows[Missing] {
        noEmailServiceURLConfig.as[ApplicationToBeDeletedNotificationsConfig](jobConfigPath)
      }
    }

    "throw a Missing exception if emailTemplateId is not specified" in new TimedJobConfigReaders {
      assertThrows[Missing] {
        noEmailTemplateIdConfig.as[ApplicationToBeDeletedNotificationsConfig](jobConfigPath)
      }
    }

    "throw a Missing exception if environmentName is not specified" in new TimedJobConfigReaders {
      assertThrows[Missing] {
        noEnvironmentNameConfig.as[ApplicationToBeDeletedNotificationsConfig](jobConfigPath)
      }
    }

    "throw a Missing exception if cutoff is not specified" in new TimedJobConfigReaders {
      assertThrows[Missing] {
        noNotificationCutoffConfig.as[ApplicationToBeDeletedNotificationsConfig](jobConfigPath)
      }
    }
  }
}
