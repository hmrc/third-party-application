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

package uk.gov.hmrc.thirdpartyapplication.config

import java.util.concurrent.TimeUnit._

import javax.inject.{Inject, Provider, Singleton}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.joda.time.format.ISODateTimeFormat
import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.ApplicationControllerConfig
import uk.gov.hmrc.thirdpartyapplication.scheduled._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationNameValidationConfig, ClientSecretServiceConfig, CredentialConfig}

import scala.concurrent.duration.{Duration, FiniteDuration}

class ConfigurationModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): List[Binding[_]] = {
    List(
      bind[UpliftVerificationExpiryJobConfig].toProvider[UpliftVerificationExpiryJobConfigProvider],
      bind[MetricsJobConfig].toProvider[MetricsJobConfigProvider],
      bind[ApiSubscriptionFieldsConfig].toProvider[ApiSubscriptionFieldsConfigProvider],
      bind[ApiStorageConfig].toProvider[ApiStorageConfigProvider],
      bind[AuthConfig].toProvider[AuthConfigProvider],
      bind[EmailConfig].toProvider[EmailConfigProvider],
      bind[TotpConfig].toProvider[TotpConfigProvider],
      bind[AwsApiGatewayConfig].toProvider[AwsApiGatewayConfigProvider],
      bind[ApiPlatformEventsConfig].toProvider[ApiPlatformEventsConfigProvider],
      bind[ThirdPartyDelegatedAuthorityConfig].toProvider[ThirdPartyDelegatedAuthorityConfigProvider],
      bind[ApplicationControllerConfig].toProvider[ApplicationControllerConfigProvider],
      bind[CredentialConfig].toProvider[CredentialConfigProvider],
      bind[ClientSecretServiceConfig].toProvider[ClientSecretServiceConfigProvider],
      bind[ApplicationNameValidationConfig].toProvider[ApplicationNameValidationConfigConfigProvider],
      bind[ResetLastAccessDateJobConfig].toProvider[ResetLastAccessDateJobConfigProvider]
    )
  }
}

object ConfigHelper {

  def getConfig[T](key: String, f: String => Option[T]): T = {
    f(key).getOrElse(throw new RuntimeException(s"[$key] is not configured!"))
  }
}

@Singleton
class UpliftVerificationExpiryJobConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[UpliftVerificationExpiryJobConfig] {

  override def get() = {
    val jobConfig = configuration.underlying.as[Option[JobConfig]]("upliftVerificationExpiryJob")
      .getOrElse(JobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true)) // scalastyle:off magic.number

    val validity: FiniteDuration = configuration.getOptional[FiniteDuration]("upliftVerificationValidity")
      .getOrElse(Duration(90, DAYS)) // scalastyle:off magic.number

    UpliftVerificationExpiryJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled, validity)
  }
}


@Singleton
class MetricsJobConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[MetricsJobConfig] {

  override def get() = {
    val jobConfig = configuration.underlying.as[Option[JobConfig]]("metricsJob")
      .getOrElse(JobConfig(FiniteDuration(2, MINUTES), FiniteDuration(1, HOURS), enabled = true)) // scalastyle:off magic.number

    MetricsJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled)
  }
}

@Singleton
class ApiSubscriptionFieldsConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration) with Provider[ApiSubscriptionFieldsConfig] {

  override def get() = {
    val url = baseUrl("api-subscription-fields")
    ApiSubscriptionFieldsConfig(url)
  }
}

@Singleton
class ApiStorageConfigProvider @Inject()(val configuration: Configuration)
  extends Provider[ApiStorageConfig] {

  override def get() = {
    val disableAwsCalls = configuration.getOptional[Boolean]("disableAwsCalls").getOrElse(false)
    ApiStorageConfig(disableAwsCalls)
  }
}

@Singleton
class AuthConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[AuthConfig] {

  override def get() = {
    val url = baseUrl("auth")
    val userRole = getString("roles.user")
    val superUserRole = getString("roles.super-user")
    val adminRole = getString("roles.admin")
    val enabled = getConfBool("auth.enabled", true)
    val canDeleteApplications: Boolean = ConfigHelper.getConfig("canDeleteApplications", configuration.getOptional[Boolean])
    val authorisationKey = getString("authorisationKey")

    AuthConfig(url, userRole, superUserRole, adminRole, enabled, canDeleteApplications, authorisationKey)
  }
}

@Singleton
class EmailConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[EmailConfig] {

  override def get() = {
    val url = baseUrl("email")
    val devHubBaseUrl = ConfigHelper.getConfig("devHubBaseUrl", configuration.getOptional[String](_))
    val devHubTitle: String = "Developer Hub"
    val environmentName: String = configuration.getOptional[String]("environmentName").getOrElse("unknown")
    EmailConfig(url, devHubBaseUrl, devHubTitle, environmentName)
  }
}

@Singleton
class TotpConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[TotpConfig] {

  override def get() = {
    val url = baseUrl("totp")
    TotpConfig(url)
  }
}

@Singleton
class AwsApiGatewayConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[AwsApiGatewayConfig] {

  override def get() = {
    val url = baseUrl("aws-gateway")
    val awsApiKey = getString("awsApiKey")
    AwsApiGatewayConfig(url, awsApiKey)
  }
}

@Singleton
class ThirdPartyDelegatedAuthorityConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[ThirdPartyDelegatedAuthorityConfig] {

  override def get() = {
    val url = baseUrl("third-party-delegated-authority")
    ThirdPartyDelegatedAuthorityConfig(url)
  }
}

@Singleton
class ApplicationControllerConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[ApplicationControllerConfig] {

  override def get() = {
    val fetchApplicationTtlInSecs: Int = ConfigHelper.getConfig("fetchApplicationTtlInSeconds", configuration.getOptional[Int])
    val fetchSubscriptionTtlInSecs: Int = ConfigHelper.getConfig("fetchSubscriptionTtlInSeconds", configuration.getOptional[Int])
    ApplicationControllerConfig(fetchApplicationTtlInSecs, fetchSubscriptionTtlInSecs)
  }
}

@Singleton
class CredentialConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[CredentialConfig] {

  override def get() = {
    val clientSecretLimit: Int = ConfigHelper.getConfig("clientSecretLimit", configuration.getOptional[Int])
    CredentialConfig(clientSecretLimit)
  }
}

@Singleton
class ClientSecretServiceConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[ClientSecretServiceConfig] {

  override def get(): ClientSecretServiceConfig = {
    val hashFunctionWorkFactor: Int = ConfigHelper.getConfig("hashFunctionWorkFactor", configuration.getOptional[Int])
    ClientSecretServiceConfig(hashFunctionWorkFactor)
  }
}

@Singleton
class ApplicationNameValidationConfigConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[ApplicationNameValidationConfig] {

  override def get() = {
    val nameBlackList: List[String] = ConfigHelper.getConfig("applicationNameBlackList", configuration.getOptional[Seq[String]]).toList
    val validateForDuplicateAppNames = ConfigHelper.getConfig("validateForDuplicateAppNames", configuration.getOptional[Boolean])

    ApplicationNameValidationConfig(nameBlackList, validateForDuplicateAppNames)
  }
}

@Singleton
class ApiPlatformEventsConfigProvider @Inject()(val configuration: Configuration)
  extends ServicesConfig(configuration)
  with Provider[ApiPlatformEventsConfig] {

    override def get(): ApiPlatformEventsConfig = {
    val url = baseUrl("api-platform-events")
    val enabled = getConfBool("api-platform-events.enabled", true)
    ApiPlatformEventsConfig(url, enabled)
  }
}

@Singleton
class ResetLastAccessDateJobConfigProvider @Inject()(configuration: Configuration)
  extends ServicesConfig(configuration)
    with Provider[ResetLastAccessDateJobConfig] {

  override def get(): ResetLastAccessDateJobConfig = {
    val dateFormatter = ISODateTimeFormat.date()

    val enabled = configuration.get[Boolean]("resetLastAccessDateJob.enabled")
    val dryRun = configuration.get[Boolean]("resetLastAccessDateJob.dryRun")
    val noLastAccessDateBeforeAsString = configuration.get[String]("resetLastAccessDateJob.noLastAccessDateBefore")

    ResetLastAccessDateJobConfig(dateFormatter.parseLocalDate(noLastAccessDateBeforeAsString), enabled, dryRun)
  }
}
