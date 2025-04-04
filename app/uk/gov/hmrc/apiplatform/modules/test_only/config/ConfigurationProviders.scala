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

package uk.gov.hmrc.apiplatform.modules.test_only.config

import javax.inject.{Inject, Provider, Singleton}

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._

import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.thirdpartyapplication.scheduled.TestApplicationsCleanupJob
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

class ConfigurationModule extends Module with ApplicationLogger {

  override def bindings(environment: Environment, configuration: Configuration): List[Binding[_]] = {
    List(
      bind[TestApplicationsCleanupJob.Config].toProvider[TestApplicationsCleanupJobConfigProvider]
    )
  }
}

object ConfigHelper {

  def getConfig[T](key: String, f: String => Option[T]): T = {
    f(key).getOrElse(throw new RuntimeException(s"[$key] is not configured!"))
  }
}

@Singleton
class TestApplicationsCleanupJobConfigProvider @Inject() (val configuration: Configuration)
    extends ServicesConfig(configuration)
    with Provider[TestApplicationsCleanupJob.Config] {

  override def get() = {
    val jobConfig = configuration.underlying.as[TestApplicationsCleanupJob.Config]("testApplicationsCleanupJob")

    TestApplicationsCleanupJob.Config(jobConfig.initialDelay, jobConfig.interval, jobConfig.enabled, jobConfig.expiryDuration)
  }
}
