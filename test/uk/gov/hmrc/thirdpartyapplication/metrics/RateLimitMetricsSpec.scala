/*
 * Copyright 2022 HM Revenue & Customs
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

import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RateLimitMetricsSpec extends AsyncHmrcSpec {

  trait Setup {
    def applicationsWithRateLimit(rateLimit: Option[RateLimitTier], numberOfApplications: Int): List[ApplicationData] = {
      def mockedApplication: ApplicationData = {
        val application: ApplicationData = mock[ApplicationData]
        when(application.rateLimitTier).thenReturn(rateLimit)

        application
      }

      List.fill(numberOfApplications)(mockedApplication)
    }

    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]

    val metricUnderTest: RateLimitMetrics = new RateLimitMetrics(mockApplicationRepository)
  }

  "metrics refresh" should {
    "update application by rate limit counts" in new Setup {
      private val numberOfBronze = 10
      private val numberOfSilver = 5
      private val numberOfGold = 2
      private val numberOfUnknown = 1

      private val applicationsToReturn: List[ApplicationData] =
        applicationsWithRateLimit(Some(RateLimitTier.BRONZE), numberOfBronze) ++
          applicationsWithRateLimit(Some(RateLimitTier.SILVER), numberOfSilver) ++
          applicationsWithRateLimit(Some(RateLimitTier.GOLD), numberOfGold) ++
          applicationsWithRateLimit(None, numberOfUnknown)

      when(mockApplicationRepository.fetchAll()).thenReturn(Future.successful(applicationsToReturn))

      private val result = await(metricUnderTest.metrics)

      result("applicationsByRateLimit.BRONZE") shouldBe numberOfBronze
      result("applicationsByRateLimit.SILVER") shouldBe numberOfSilver
      result("applicationsByRateLimit.GOLD") shouldBe numberOfGold
      result("applicationsByRateLimit.UNKNOWN") shouldBe numberOfUnknown
    }
  }
}
