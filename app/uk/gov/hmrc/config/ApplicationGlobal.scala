/*
 * Copyright 2018 HM Revenue & Customs
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

import com.typesafe.config.Config
import play.api._
import play.api.mvc.EssentialFilter
import uk.gov.hmrc.api.config.{ServiceLocatorConfig, ServiceLocatorRegistration}
import uk.gov.hmrc.api.connector.ServiceLocatorConnector
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.hooks.HttpHooks
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.config.{AppName, ControllerConfig, RunMode}
import uk.gov.hmrc.play.http.ws._
import uk.gov.hmrc.play.microservice.bootstrap.DefaultMicroserviceGlobal
import uk.gov.hmrc.play.microservice.config.LoadAuditingConfig
import uk.gov.hmrc.play.microservice.filters._
import uk.gov.hmrc.play.scheduling.{ExclusiveScheduledJob, RunningOfScheduledJobs, ScheduledJob}
import uk.gov.hmrc.scheduled.{RefreshSubscriptionsScheduledJob, UpliftVerificationExpiryJob}

object ApplicationGlobal extends DefaultMicroserviceGlobal with RunMode with RunningOfScheduledJobs
  with ServiceLocatorRegistration with ServiceLocatorConfig {

  lazy val injector = Play.current.injector
  lazy val appContext = injector.instanceOf[AppContext]


  override def loggingFilter: LoggingFilter = MicroserviceLoggingFilter

  override def microserviceAuditFilter: AuditFilter = MicroserviceAuditFilter

  override protected def defaultMicroserviceFilters: Seq[EssentialFilter] = Seq(
    Some(metricsFilter),
    Some(microserviceAuditFilter),
    Some(loggingFilter),
    Some(DefaultToNoCacheFilter),
    Some(RecoveryFilter)).flatten

  override val hc = HeaderCarrier()
  override val slConnector = ServiceLocatorConnector(WSHttp)
  override lazy val registrationEnabled = appContext.publishApiDefinition

  override def authFilter = None

  override def auditConnector: AuditConnector = MicroserviceAuditConnector

  override def microserviceMetricsConfig(implicit app: Application): Option[Configuration] =
    app.configuration.getConfig(s"$env.microservice.metrics")

  override lazy val scheduledJobs: Seq[ScheduledJob] = {
    val upliftJob: Seq[ExclusiveScheduledJob] = if (appContext.upliftVerificationExpiryJobConfig.enabled) {
      Seq(injector.instanceOf[UpliftVerificationExpiryJob])
    } else {
      Seq.empty
    }

    val refreshJob = if (appContext.refreshSubscriptionsJobConfig.enabled) {
      Seq(injector.instanceOf[RefreshSubscriptionsScheduledJob])
    } else {
      Seq.empty
    }
    upliftJob ++ refreshJob
  }
}

object ControllerConfiguration extends ControllerConfig {

  import net.ceedubs.ficus.Ficus._

  lazy val controllerConfigs = Play.current.configuration.underlying.as[Config]("controllers")
}

object MicroserviceAuditFilter extends AuditFilter with AppName with MicroserviceFilterSupport {
  override val auditConnector = MicroserviceAuditConnector

  override def controllerNeedsAuditing(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsAuditing
}

object MicroserviceLoggingFilter extends LoggingFilter with MicroserviceFilterSupport {
  override def controllerNeedsLogging(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsLogging
}

object MicroserviceAuditConnector extends AuditConnector with RunMode {
  override lazy val auditingConfig = LoadAuditingConfig(s"$env.auditing")
}

trait Hooks extends HttpHooks with HttpAuditing {
  override val hooks = Seq(AuditingHook)
  override lazy val auditConnector: AuditConnector = MicroserviceAuditConnector
}

trait WSHttp extends HttpGet with WSGet with HttpPut with WSPut with HttpPost with WSPost with HttpDelete with WSDelete with Hooks with AppName
object WSHttp extends WSHttp