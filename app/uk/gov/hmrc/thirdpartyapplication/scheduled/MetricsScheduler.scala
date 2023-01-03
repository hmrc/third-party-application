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

import akka.actor.ActorSystem
import com.kenshoo.play.metrics.Metrics
import play.api.Configuration
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.mongo.metrix.{MetricOrchestrator, MetricRepository}
import uk.gov.hmrc.thirdpartyapplication.metrics._

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class MetricsScheduler @Inject() (
    actorSystem: ActorSystem,
    configuration: Configuration,
    metrics: Metrics,
    apisWithSubscriptionCount: ApisWithSubscriptionCount,
    applicationCount: ApplicationCount,
    applicationWithSubscriptionCount: ApplicationsWithSubscriptionCount,
    missingMongoFields: MissingMongoFields,
    rateLimitMetrics: RateLimitMetrics,
    lockRepository: LockRepository,
    metricRepository: MetricRepository
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  lazy val refreshInterval: FiniteDuration = configuration.getOptional[FiniteDuration]("queue.metricsGauges.interval").getOrElse(10.minutes)
  lazy val initialDelay: FiniteDuration    = configuration.getOptional[FiniteDuration]("queue.initialDelay").getOrElse(2.minutes)
  lazy val isEnabled: Boolean              = configuration.getOptional[Boolean]("metricsJob.enabled").getOrElse(false)

  val lockService: LockService = LockService(lockRepository = lockRepository, lockId = "queue", ttl = refreshInterval)

  val metricOrchestrator = new MetricOrchestrator(
    metricSources = List(apisWithSubscriptionCount, applicationCount, applicationWithSubscriptionCount, missingMongoFields, rateLimitMetrics),
    lockService = lockService,
    metricRepository = metricRepository,
    metricRegistry = metrics.defaultRegistry
  )

  if (isEnabled) {
    actorSystem.scheduler.scheduleWithFixedDelay(initialDelay, refreshInterval)(() => {
      metricOrchestrator
        .attemptMetricRefresh()
        .map(_.log)
        .recover({ case e: RuntimeException =>
          logger.error(s"[METRIC] An error occurred processing metrics: ${e.getMessage}", e)
        })
    })
  }
}
