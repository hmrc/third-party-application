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

import javax.inject.Inject
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.thirdpartyapplication.models.APIIdentifier
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

import scala.concurrent.{ExecutionContext, Future}

class SubscriptionMetrics @Inject()(val subscriptionRepository: SubscriptionRepository) extends MetricSource {
  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    def subscriptionCountKey(apiName: String): String = s"subscriptionCount.$apiName"

    numberOfSubscriptionsByApi.map(subscriptionCounts => subscriptionCounts.map(count => subscriptionCountKey(count._1) -> count._2))
  }

  def numberOfSubscriptionsByApi(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    def apiName(apiIdentifier: APIIdentifier): String = s"${apiIdentifier.context}--${apiIdentifier.version}"

    subscriptionRepository.findAll()
      .map(subscriptions => subscriptions.map(subscription => apiName(subscription.apiIdentifier) -> subscription.applications.size).toMap)
  }
}
