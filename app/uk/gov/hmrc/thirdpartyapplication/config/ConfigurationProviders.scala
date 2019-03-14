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

import java.util.concurrent.TimeUnit._

import javax.inject.{Inject, Provider, Singleton}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.{ApplicationControllerConfig, DocumentationConfig}
import uk.gov.hmrc.thirdpartyapplication.models.TrustedApplicationsConfig
import uk.gov.hmrc.thirdpartyapplication.scheduled.{JobConfig, RefreshSubscriptionsJobConfig, UpliftVerificationExpiryJobConfig}
import uk.gov.hmrc.thirdpartyapplication.services.CredentialConfig

import scala.concurrent.duration.{Duration, FiniteDuration}

class ConfigurationModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[ServiceLocatorRegistrationConfig].toProvider[ServiceLocatorRegistrationConfigProvider],
      bind[ServiceLocatorConfig].toProvider[ServiceLocatorConfigProvider],
      bind[DocumentationConfig].toProvider[DocumentationConfigProvider],
      bind[RefreshSubscriptionsJobConfig].toProvider[RefreshSubscriptionsJobConfigProvider],
      bind[UpliftVerificationExpiryJobConfig].toProvider[UpliftVerificationExpiryJobConfigProvider],
      bind[ApiDefinitionConfig].toProvider[ApiDefinitionConfigProvider],
      bind[ApiSubscriptionFieldsConfig].toProvider[ApiSubscriptionFieldsConfigProvider],
      bind[ApiStorageConfig].toProvider[ApiStorageConfigProvider],
      bind[AuthConfig].toProvider[AuthConfigProvider],
      bind[EmailConfig].toProvider[EmailConfigProvider],
      bind[TotpConfig].toProvider[TotpConfigProvider],
      bind[Wso2ApiStoreConfig].toProvider[Wso2ApiStoreConfigProvider],
      bind[ThirdPartyDelegatedAuthorityConfig].toProvider[ThirdPartyDelegatedAuthorityConfigProvider],
      bind[ApplicationControllerConfig].toProvider[ApplicationControllerConfigProvider],
      bind[TrustedApplicationsConfig].toProvider[TrustedApplicationsConfigProvider],
      bind[CredentialConfig].toProvider[CredentialConfigProvider]
    )
  }
}

object ConfigHelper {

  def getConfig[T](key: String, f: String => Option[T]): T = {
    f(key).getOrElse(throw new RuntimeException(s"[$key] is not configured!"))
  }
}

@Singleton
class ServiceLocatorRegistrationConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ServiceLocatorRegistrationConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val registrationEnabled = getConfBool("service-locator.enabled", defBool = true)
    ServiceLocatorRegistrationConfig(registrationEnabled)
  }
}

@Singleton
class ServiceLocatorConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[ServiceLocatorConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val appName = getString("appName")
    val appUrl = getString("appUrl")
    val serviceLocatorBaseUrl = baseUrl("service-locator")
    ServiceLocatorConfig(appName, appUrl, serviceLocatorBaseUrl)
  }
}

@Singleton
class DocumentationConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[DocumentationConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val publishApiDefinition = runModeConfiguration.getBoolean("publishApiDefinition").getOrElse(false)
    val apiContext = runModeConfiguration.getString("api.context").getOrElse("third-party-application")
    val access = runModeConfiguration.getConfig(s"api.access")
    DocumentationConfig(publishApiDefinition, apiContext, access)
  }
}

@Singleton
class RefreshSubscriptionsJobConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[RefreshSubscriptionsJobConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val jobConfig = runModeConfiguration.underlying.as[Option[JobConfig]](s"$env.refreshSubscriptionsJob")
      .getOrElse(JobConfig(FiniteDuration(120, SECONDS), FiniteDuration(60, DAYS), enabled = true)) // scalastyle:off magic.number
    RefreshSubscriptionsJobConfig(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled)
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
    ApiStorageConfig(skipWso2)
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
    AuthConfig(url, userRole, superUserRole, adminRole, enabled)
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
class Wso2ApiStoreConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[Wso2ApiStoreConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val url = baseUrl("wso2-store")
    val adminUsername = getConfString("wso2-store.username", "admin")
    Wso2ApiStoreConfig(url, adminUsername)
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
class TrustedApplicationsConfigProvider @Inject()(val runModeConfiguration: Configuration, environment: Environment)
  extends Provider[TrustedApplicationsConfig] with ServicesConfig {

  override protected def mode = environment.mode

  override def get() = {
    val trustedApplications: Seq[String] = ConfigHelper.getConfig(s"$env.trustedApplications", runModeConfiguration.getStringSeq)
    TrustedApplicationsConfig(trustedApplications)
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



