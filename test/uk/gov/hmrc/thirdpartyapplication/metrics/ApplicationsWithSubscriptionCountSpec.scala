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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationsWithSubscriptionCountSpec extends AsyncHmrcSpec {

  trait Setup {

    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]

    val metricUnderTest: ApplicationsWithSubscriptionCount = new ApplicationsWithSubscriptionCount(mockApplicationRepository)
  }
  "metrics count" should {
    val expectedResult = Map("applicationsWithSubscriptionCount.Test App 1" -> 2)

    "refresh application with subscription counts" in new Setup {
      when(mockApplicationRepository.getApplicationWithSubscriptionCount())
        .thenReturn(Future.successful(expectedResult))

      val result: Map[String, Int] = await(metricUnderTest.metrics)

      result shouldBe expectedResult
    }
  }
}
