/*
 * Copyright 2021 HM Revenue & Customs
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

import uk.gov.hmrc.thirdpartyapplication.models.{ApiIdentifier, SubscriptionData}
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, MetricsHelper}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationId

class ApisWithSubscriptionCountSpec extends AsyncHmrcSpec with MetricsHelper {

  trait Setup {
    val mockSubscriptionsRepository: SubscriptionRepository = mock[SubscriptionRepository]

    val metricUnderTest: ApisWithSubscriptionCount = new ApisWithSubscriptionCount(mockSubscriptionsRepository)
  }

  "metrics refresh" should {
    def subscriptionDetails(subscription: (String, String, Int)): SubscriptionData =
      SubscriptionData(new ApiIdentifier(subscription._1, subscription._2), Seq.fill(subscription._3)(ApplicationId.random()).toSet)

    def expectedAPIName(subscription: (String, String, Int)): String =
      s"apisWithSubscriptionCountV1.${sanitiseGrafanaNodeName(subscription._1)}.${sanitiseGrafanaNodeName(subscription._2)}"

    "update subscription counts" in new Setup {
      private val api1v1 = ("apiOne", "1.0", 5)
      private val api1v2 = ("api(One)", "2.0", 10)
      private val api2 = ("route/apiTwo", "1.0", 100)

      when(mockSubscriptionsRepository.findAll())
        .thenReturn(Future.successful(
          List(subscriptionDetails(api1v1), subscriptionDetails(api1v2), subscriptionDetails(api2))))

      private val result = await(metricUnderTest.metrics)

      result(expectedAPIName(api1v1)) shouldBe api1v1._3
      result(expectedAPIName(api1v2)) shouldBe api1v2._3
      result(expectedAPIName(api2)) shouldBe api2._3
    }
  }
}
