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

package uk.gov.hmrc.config

import java.util.concurrent.TimeUnit._
import javax.inject.Inject

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import uk.gov.hmrc.models.ApplicationData
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.scheduled.JobConfig

import scala.concurrent.duration.{Duration, FiniteDuration}

class AppContext @Inject()(val config: Config) extends ServicesConfig {

  lazy val devHubBaseUrl = getConfig(s"$env.devHubBaseUrl")
  lazy val skipWso2: Boolean = getConfig(s"$env.skipWso2", runModeConfiguration.getBoolean)
  lazy val clientSecretLimit: Int = getConfig(s"clientSecretLimit", runModeConfiguration.getInt)
  lazy val trustedApplications: Seq[String] = getConfig(s"$env.trustedApplications", runModeConfiguration.getStringSeq)

  lazy val upliftVerificationValidity: FiniteDuration = config.as[Option[FiniteDuration]]("upliftVerificationValidity")
    .getOrElse(Duration(90, DAYS)) // scalastyle:off magic.number
  lazy val upliftVerificationExpiryJobConfig = config.as[Option[JobConfig]](s"$env.upliftVerificationExpiryJob")
    .getOrElse(JobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true)) // scalastyle:off magic.number

  lazy val refreshSubscriptionsJobConfig = config.as[Option[JobConfig]](s"$env.refreshSubscriptionsJob")
    .getOrElse(JobConfig(FiniteDuration(120, SECONDS), FiniteDuration(60, DAYS), enabled = true)) // scalastyle:off magic.number

  lazy val devHubTitle: String = "Developer Hub"

  lazy val fetchApplicationTtlInSecs : Int = getConfig("fetchApplicationTtlInSeconds", runModeConfiguration.getInt)
  lazy val fetchSubscriptionTtlInSecs : Int = getConfig("fetchSubscriptionTtlInSeconds", runModeConfiguration.getInt)

  lazy val publishApiDefinition = runModeConfiguration.getBoolean("publishApiDefinition").getOrElse(false)
  lazy val apiContext = runModeConfiguration.getString("api.context").getOrElse("third-party-application")
  lazy val access = runModeConfiguration.getConfig(s"api.access")

  override def toString() = {
    "AppContext{" + (
      Seq(s"environment=$env",s"skipWso2=$skipWso2",
          s"clientSecretLimit=$clientSecretLimit",
          s"upliftVerificationValidity=$upliftVerificationValidity",
          s"upliftVerificationExpiryJobConfig=$upliftVerificationExpiryJobConfig",
          s"trustedApplications=$trustedApplications",
          s"fetchApplicationTtlInSecs=$fetchApplicationTtlInSecs",
          s"fetchSubscriptionTtlInSecs=$fetchSubscriptionTtlInSecs"
      ) mkString ",") + "}"
  }

  private def getConfig(key: String) = runModeConfiguration.getString(key)
    .getOrElse(throw new RuntimeException(s"[$key] is not configured!"))

  private def getConfig[T](key: String, block: String => Option[T]) = block(key)
    .getOrElse(throw new RuntimeException(s"[$key] is not configured!"))

  def isTrusted(application: ApplicationData): Boolean = trustedApplications.contains(application.id.toString)
}
