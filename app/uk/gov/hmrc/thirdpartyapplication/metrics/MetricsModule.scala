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

package uk.gov.hmrc.thirdpartyapplication.metrics

import akka.actor.ActorSystem
import com.kenshoo.play.metrics.Metrics
import javax.inject.{Inject, Provider, Singleton}
import org.joda.time.Duration
import play.api.inject.{ApplicationLifecycle, Binding, Module}
import play.api.{Configuration, Environment, Logger}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.DB
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}
import uk.gov.hmrc.metrix.MetricOrchestrator
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.metrix.persistence.MongoMetricRepository

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class MetricsModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[MetricOrchestrator].toProvider[MetricsOrchestratorProvider],
      bind[MetricsSources].toProvider[MetricsSourcesProvider],
      bind[MetricsScheduler].toSelf.eagerly)
  }
}

@Singleton
class MetricsScheduler @Inject()(env: Environment,
                                 lifecycle: ApplicationLifecycle,
                                 actorSystem: ActorSystem,
                                 configuration: Configuration,
                                 metricOrchestrator: MetricOrchestrator)(implicit val ec: ExecutionContext) {
  private val initialDelay = 2 minutes
  private val interval = 1 hour

  Logger.info(s"Configuring Metrics schedule to run every $interval")

  actorSystem.scheduler.schedule(initialDelay, interval) {
    Logger.info(s"Running Metrics Collection Process")
    metricOrchestrator
      .attemptToUpdateAndRefreshMetrics()
      .map(_.andLogTheResult())
      .recover { case e: RuntimeException => Logger.error(s"An error occurred processing metrics: ${e.getMessage}", e) }
  }

  lifecycle.addStopHook(() => Future {
    actorSystem.terminate()
  })
}

@Singleton
class MetricsOrchestratorProvider @Inject()(configuration: Configuration,
                                            metricsSources: MetricsSources,
                                            metrics: Metrics,
                                            mongoComponent: ReactiveMongoComponent) extends Provider[MetricOrchestrator] {

  implicit val mongo: () => DB = mongoComponent.mongoConnector.db

  val Lock: ExclusiveTimePeriodLock = new ExclusiveTimePeriodLock {
    override def repo: LockRepository = new LockRepository()
    override def lockId: String = "MetricsLock"
    override def holdLockFor: Duration =  Duration.standardMinutes(2)
  }

  override def get(): MetricOrchestrator = {
    new MetricOrchestrator(metricsSources.asList, Lock, new MongoMetricRepository(), metrics.defaultRegistry)
  }
}

@Singleton
class MetricsSourcesProvider @Inject()(rateLimitMetrics: RateLimitMetrics,
                                       subscriptionMetrics: SubscriptionMetrics,
                                       missingMongoFields: MissingMongoFields) extends Provider[MetricsSources] {
  override def get(): MetricsSources = MetricsSources(rateLimitMetrics, subscriptionMetrics, missingMongoFields)
}

case class MetricsSources(metricSources: MetricSource*) {
  def asList: List[MetricSource] = metricSources.toList
}