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

package uk.gov.hmrc.thirdpartyapplication.metrics

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.google.inject.Singleton

import uk.gov.hmrc.mongo.metrix.MetricSource

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class RateLimitMetrics @Inject() (applicationRepository: ApplicationRepository)
    extends MetricSource
    with ApplicationLogger {

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    numberOfApplicationsByRateLimit.map(applicationCounts =>
      applicationCounts.map(rateLimit => {
        applicationsByRateLimitKey(rateLimit._1) -> rateLimit._2
      })
    )
  }

  def numberOfApplicationsByRateLimit(implicit ec: ExecutionContext): Future[Map[Option[RateLimitTier], Int]] = {
    val result = applicationRepository.fetchAll().map(applications => applications.groupBy(_.rateLimitTier).view.mapValues(_.size).toMap)

    result.onComplete({
      case Success(v) => logger.info(s"[METRIC] Success - RateLimitMetrics: ${v}")
      case Failure(e) => logger.info(s"[METRIC] Error - RateLimitMetrics - error is: ${e.getMessage}")
    })
    result
  }

  private def applicationsByRateLimitKey(rateLimit: Option[RateLimitTier]): String = {
    val rateLimitString = if (rateLimit.isDefined) rateLimit.get.toString else "UNKNOWN"
    s"applicationsByRateLimit.$rateLimitString"
  }
}
