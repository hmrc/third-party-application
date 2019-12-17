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

package unit.uk.gov.hmrc.thirdpartyapplication.metrics

import java.util.UUID

import org.mockito.Mockito.when
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.metrics.SubscriptionMetrics
import uk.gov.hmrc.thirdpartyapplication.models.{APIIdentifier, SubscriptionData}
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SubscriptionMetricsSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar {

  trait Setup {
    val mockSubscriptionsRepository: SubscriptionRepository = mock[SubscriptionRepository]

    val metricUnderTest: SubscriptionMetrics = new SubscriptionMetrics(mockSubscriptionsRepository)
  }

  "metrics refresh" should {
    def subscriptionDetails(subscription: (String, String, Int)): SubscriptionData =
      SubscriptionData(new APIIdentifier(subscription._1, subscription._2), Seq.fill(subscription._3)(UUID.randomUUID()).toSet)

    def expectedAPIName(subscription: (String, String, Int)): String =
      s"subscriptionCount2.${subscription._1.replace("/", " ")}.${subscription._2.replace(".", "-")}"

    "update subscription counts" in new Setup {
      private val api1v1 = ("apiOne", "1.0", 5)
      private val api1v2 = ("apiOne", "2.0", 10)
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
