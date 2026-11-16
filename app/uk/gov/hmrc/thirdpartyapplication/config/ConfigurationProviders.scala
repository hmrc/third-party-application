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

import java.util.concurrent.TimeUnit.*
import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.duration.{Duration, FiniteDuration}

import pureconfig.*

import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ClientSecretsHashingConfig
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.thirdpartyapplication.connector.*
import uk.gov.hmrc.thirdpartyapplication.controllers.ApplicationControllerConfig
import uk.gov.hmrc.thirdpartyapplication.scheduled.*
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationNamingService, CredentialConfig, TermsOfUseInvitationConfig}

class ConfigurationModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): List[Binding[?]] = {
    List(
      bind[ApiSubscriptionFieldsConnector.Config].toProvider[ApiSubscriptionFieldsConfigProvider],
      bind[AuthControlConfig].toProvider[AuthControlConfigProvider],
      bind[EmailConnector.Config].toProvider[EmailConfigProvider],
      bind[TotpConnector.Config].toProvider[TotpConfigProvider],
      bind[ApiPlatformEventsConnector.Config].toProvider[ApiPlatformEventsConfigProvider],
      bind[ThirdPartyDelegatedAuthorityConnector.Config].toProvider[ThirdPartyDelegatedAuthorityConfigProvider],
      bind[ApplicationControllerConfig].toProvider[ApplicationControllerConfigProvider],
      bind[CredentialConfig].toProvider[CredentialConfigProvider],
      bind[ClientSecretsHashingConfig].toProvider[ClientSecretsHashingConfigProvider],
      bind[ApplicationNamingService.Config].toProvider[ApplicationNamingServiceConfigProvider],
      bind[TermsOfUseInvitationConfig].toProvider[TermsOfUseInvitationConfigProvider]
    )
  }
}

object ConfigHelper {

  def getConfig[T](key: String, f: String => Option[T]): T = {
    f(key).getOrElse(throw new RuntimeException(s"[$key] is not configured!"))
  }

  def getJobConfig(configuration: Configuration, key: String): Option[JobConfig] = {
    val conf = configuration.underlying.atKey(key)
    ConfigSource.fromConfig(conf).load[JobConfig].toOption
  }
}

@Singleton
class ApiSubscriptionFieldsConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration) with Provider[ApiSubscriptionFieldsConnector.Config] {

  override def get() = {
    val url = baseUrl("api-subscription-fields")
    ApiSubscriptionFieldsConnector.Config(url)
  }
}

@Singleton
class AuthControlConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[AuthControlConfig] {

  override def get() = {
    val enabled                        = getConfBool("auth.enabled", true)
    val canDeleteApplications: Boolean = ConfigHelper.getConfig("canDeleteApplications", configuration.getOptional[Boolean])
    val authorisationKey               = getString("authorisationKey")

    AuthControlConfig(enabled, canDeleteApplications, authorisationKey)
  }
}

@Singleton
class EmailConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[EmailConnector.Config] {

  override def get() = {
    val url                     = baseUrl("email")
    val devHubBaseUrl           = ConfigHelper.getConfig("devHubBaseUrl", configuration.getOptional[String](_))
    val devHubTitle: String     = "Developer Hub"
    val environmentName: String = configuration.getOptional[String]("environmentName").getOrElse("unknown")
    EmailConnector.Config(url, devHubBaseUrl, devHubTitle, environmentName)
  }
}

@Singleton
class TotpConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[TotpConnector.Config] {

  override def get() = {
    val url = baseUrl("totp")
    TotpConnector.Config(url)
  }
}

@Singleton
class ThirdPartyDelegatedAuthorityConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ThirdPartyDelegatedAuthorityConnector.Config] {

  override def get() = {
    val url = baseUrl("third-party-delegated-authority")
    ThirdPartyDelegatedAuthorityConnector.Config(url)
  }
}

@Singleton
class ApplicationControllerConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ApplicationControllerConfig] {

  override def get() = {
    val fetchApplicationTtlInSecs: Int  = ConfigHelper.getConfig("fetchApplicationTtlInSeconds", configuration.getOptional[Int])
    val fetchSubscriptionTtlInSecs: Int = ConfigHelper.getConfig("fetchSubscriptionTtlInSeconds", configuration.getOptional[Int])
    ApplicationControllerConfig(fetchApplicationTtlInSecs, fetchSubscriptionTtlInSecs)
  }
}

@Singleton
class CredentialConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[CredentialConfig] {

  override def get() = {
    val clientSecretLimit: Int = ConfigHelper.getConfig("clientSecretLimit", configuration.getOptional[Int])
    CredentialConfig(clientSecretLimit)
  }
}

@Singleton
class TermsOfUseInvitationConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[TermsOfUseInvitationConfig] {

  override def get() = {
    val daysUntilDueWhenCreated: FiniteDuration = configuration.getOptional[FiniteDuration]("termsOfUseDaysUntilDueWhenCreated")
      .getOrElse(Duration(21, DAYS))
    val daysUntilDueWhenReset: FiniteDuration   = configuration.getOptional[FiniteDuration]("termsOfUseDaysUntilDueWhenReset")
      .getOrElse(Duration(30, DAYS))

    TermsOfUseInvitationConfig(daysUntilDueWhenCreated, daysUntilDueWhenReset)
  }
}

@Singleton
class ClientSecretsHashingConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ClientSecretsHashingConfig] {

  override def get(): ClientSecretsHashingConfig = {
    new ClientSecretsHashingConfig(configuration.underlying)
  }
}

@Singleton
class ApplicationNamingServiceConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ApplicationNamingService.Config] {

  override def get() = {
    val nameDenyList: List[String]   = ConfigHelper.getConfig("applicationNameDenyList", configuration.getOptional[Seq[String]]).toList
    val validateForDuplicateAppNames = ConfigHelper.getConfig("validateForDuplicateAppNames", configuration.getOptional[Boolean])

    ApplicationNamingService.Config(nameDenyList, validateForDuplicateAppNames)
  }
}

@Singleton
class ApiPlatformEventsConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[ApiPlatformEventsConnector.Config] {

  override def get(): ApiPlatformEventsConnector.Config = {
    val url     = baseUrl("api-platform-events")
    val enabled = getConfBool("api-platform-events.enabled", true)
    ApiPlatformEventsConnector.Config(url, enabled)
  }
}
