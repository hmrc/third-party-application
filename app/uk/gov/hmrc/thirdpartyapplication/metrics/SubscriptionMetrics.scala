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

package uk.gov.hmrc.thirdpartyapplication.metrics

import javax.inject.Inject
import play.api.Logger
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.thirdpartyapplication.models.APIIdentifier
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.util.MetricsHelper

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SubscriptionMetrics @Inject()(val subscriptionRepository: SubscriptionRepository) extends MetricSource with MetricsHelper {
  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    Logger.info(s"Pomegranate - Starting - SubscriptionMetrics.metrics() about to calculate subscriptionCount map")
    def subscriptionCountKey(apiName: String): String = s"subscriptionCount2.$apiName"

    val result = numberOfSubscriptionsByApi.map(subscriptionCounts => subscriptionCounts.map(count => subscriptionCountKey(count._1) -> count._2))
    result.onComplete({
        case Success(v) =>
          Logger.info(s"Pomegranate - Future.success - SubscriptionMetrics.metrics() - api versions are: ${v.keys.size}" )

        case Failure(e) =>
          Logger.info(s"Pomegranate - Future.failure - SubscriptionMetrics.metrics() - error is: ${e.toString}" )
    })
    Logger.info(s"Pomegranate - Finish - SubscriptionMetrics.metrics()")
    result
  }

  def numberOfSubscriptionsByApi(implicit ec: ExecutionContext): Future[Map[String, Int]] = {

    def apiName(apiIdentifier: APIIdentifier): String = s"${sanitiseGrafanaNodeName(apiIdentifier.context)}.${sanitiseGrafanaNodeName(apiIdentifier.version)}"

    subscriptionRepository.findAll()
      .map(subscriptions => subscriptions.map(subscription => apiName(subscription.apiIdentifier) -> subscription.applications.size).toMap)
  }
}
