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

import play.api.libs.json.OFormat
import uk.gov.hmrc.mongo.metrix.MetricSource

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApiIdentifier
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.util.MetricsHelper

@Singleton
class ApisWithSubscriptionCount @Inject() (val subscriptionRepository: SubscriptionRepository)
    extends MetricSource
    with MetricsHelper
    with ApplicationLogger {

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    def subscriptionCountKey(apiName: String): String = s"apisWithSubscriptionCountV1.$apiName"

    val result = numberOfSubscriptionsByApi
      .map(_.map {
        case (apiName, count) => subscriptionCountKey(apiName) -> count
      })

    result.onComplete({
      case Success(v) => logger.info(s"[METRIC] Success - ApisWithSubscriptionCount - api versions are: ${v.keys.size}")
      case Failure(e) => logger.info(s"[METRIC] Error - ApisWithSubscriptionCount - error is: ${e.toString}")
    })
    result
  }

  def numberOfSubscriptionsByApi(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    def apiName(apiIdentifier: ApiIdentifier): String =
      s"""${sanitiseGrafanaNodeName(apiIdentifier.context.value)}.${sanitiseGrafanaNodeName(apiIdentifier.versionNbr.value)}"""

    subscriptionRepository.getSubscriptionCountByApiCheckingApplicationExists
      .map(subscriptions =>
        subscriptions
          .map { subscriptionCountByApi => apiName(subscriptionCountByApi._id) -> subscriptionCountByApi.count }
          .toMap
      )
  }
}

case class SubscriptionCountByApi(_id: ApiIdentifier, count: Int)

object SubscriptionCountByApi {
  import play.api.libs.json.Json

  implicit val subscriptionCountByApi: OFormat[SubscriptionCountByApi] = Json.format[SubscriptionCountByApi]
}
