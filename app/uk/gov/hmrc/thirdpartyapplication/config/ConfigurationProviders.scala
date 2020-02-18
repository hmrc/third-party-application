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

import java.util.concurrent.TimeUnit._

import javax.inject.{Inject, Provider, Singleton}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.ApplicationControllerConfig
import uk.gov.hmrc.thirdpartyapplication.scheduled._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationNameValidationConfig, CredentialConfig}

import scala.collection.JavaConverters._
import scala.concurrent.duration.{Duration, FiniteDuration}

class ConfigurationModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): List[Binding[_]] = {
    List(
      bind[UpliftVerificationExpiryJobConfig].toProvider[UpliftVerificationExpiryJobConfigProvider],
      bind[ApiDefinitionConfig].toProvider[ApiDefinitionConfigProvider],
      bind[ApiSubscriptionFieldsConfig].toProvider[ApiSubscriptionFieldsConfigProvider],
      bind[ApiStorageConfig].toProvider[ApiStorageConfigProvider],
      bind[AuthConfig].toProvider[AuthConfigProvider],
      bind[EmailConfig].toProvider[EmailConfigProvider],
      bind[TotpConfig].toProvider[TotpConfigProvider],
      bind[AwsApiGatewayConfig].toProvider[AwsApiGatewayConfigProvider],
      bind[ThirdPartyDelegatedAuthorityConfig].toProvider[ThirdPartyDelegatedAuthorityConfigProvider],
      bind[ThirdPartyDeveloperConfig].toProvider[ThirdPartyDeveloperConfigProvider],
      bind[ApplicationControllerConfig].toProvider[ApplicationControllerConfigProvider],
      bind[CredentialConfig].toProvider[CredentialConfigProvider],
      bind[ApplicationNameValidationConfig].toProvider[ApplicationNameValidationConfigConfigProvider]
    )
  }
}

object ConfigHelper {

  def getConfig[T](key: String, f: String => Option[T]): T = {
    f(key).getOrElse(throw new RuntimeException(s"[$key] is not configured!"))
  }
}

@Singleton
class UpliftVerificationExpiryJobConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[UpliftVerificationExpiryJobConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {

    val jobConfig = runModeConfiguration.underlying.as[Option[JobConfig]](s"$env.upliftVerificationExpiryJob")
      .getOrElse(JobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true)) // scalastyle:off magic.number
    val validity: FiniteDuration = runModeConfiguration.underlying.as[Option[FiniteDuration]]("upliftVerificationValidity")
      .getOrElse(Duration(90, DAYS)) // scalastyle:off magic.number

    UpliftVerificationExpiryJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled, validity)
  }
}

@Singleton
class ApiDefinitionConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ApiDefinitionConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("api-definition")
    ApiDefinitionConfig(url)
  }
}

@Singleton
class ApiSubscriptionFieldsConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ApiSubscriptionFieldsConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("api-subscription-fields")
    ApiSubscriptionFieldsConfig(url)
  }
}

@Singleton
class ApiStorageConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ApiStorageConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val skipWso2 =
      runModeConfiguration.getBoolean(s"$env.skipWso2").
      getOrElse(runModeConfiguration.getBoolean("skipWso2").
        getOrElse(false))
    val awsOnly = runModeConfiguration.getBoolean("awsOnly").getOrElse(false)
    ApiStorageConfig(skipWso2, awsOnly)
  }
}

@Singleton
class AuthConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[AuthConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("auth")
    val userRole = getString("roles.user")
    val superUserRole = getString("roles.super-user")
    val adminRole = getString("roles.admin")
    val enabled = getConfBool("auth.enabled", true)

    val canDeleteApplications: Boolean = ConfigHelper.getConfig("canDeleteApplications", runModeConfiguration.getBoolean)

    AuthConfig(url, userRole, superUserRole, adminRole, enabled, canDeleteApplications)
  }
}

@Singleton
class EmailConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[EmailConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("email")
    val devHubBaseUrl = ConfigHelper.getConfig(s"$env.devHubBaseUrl", runModeConfiguration.getString(_))
    val devHubTitle: String = "Developer Hub"
    EmailConfig(url, devHubBaseUrl, devHubTitle)
  }
}

@Singleton
class TotpConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[TotpConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("totp")
    TotpConfig(url)
  }
}

@Singleton
class AwsApiGatewayConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[AwsApiGatewayConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("aws-gateway")
    val awsApiKey = getString("awsApiKey")
    AwsApiGatewayConfig(url, awsApiKey)
  }
}

@Singleton
class ThirdPartyDelegatedAuthorityConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ThirdPartyDelegatedAuthorityConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("third-party-delegated-authority")
    ThirdPartyDelegatedAuthorityConfig(url)
  }
}

@Singleton
class ThirdPartyDeveloperConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ThirdPartyDeveloperConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("third-party-developer")
    ThirdPartyDeveloperConfig(url)
  }
}


@Singleton
class ApplicationControllerConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ApplicationControllerConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val fetchApplicationTtlInSecs: Int = ConfigHelper.getConfig("fetchApplicationTtlInSeconds", runModeConfiguration.getInt)
    val fetchSubscriptionTtlInSecs: Int = ConfigHelper.getConfig("fetchSubscriptionTtlInSeconds", runModeConfiguration.getInt)
    ApplicationControllerConfig(fetchApplicationTtlInSecs, fetchSubscriptionTtlInSecs)
  }
}

@Singleton
class CredentialConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[CredentialConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val clientSecretLimit: Int = ConfigHelper.getConfig(s"clientSecretLimit", runModeConfiguration.getInt)
    CredentialConfig(clientSecretLimit)
  }
}

@Singleton
class ApplicationNameValidationConfigConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ApplicationNameValidationConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val nameBlackList: List[String] = ConfigHelper.getConfig("applicationNameBlackList", runModeConfiguration.getStringList).asScala.toList
    val validateForDuplicateAppNames = ConfigHelper.getConfig("validateForDuplicateAppNames", runModeConfiguration.getBoolean)

    ApplicationNameValidationConfig(nameBlackList, validateForDuplicateAppNames)
  }
}
