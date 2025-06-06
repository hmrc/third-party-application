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

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util._

class RateLimitMetricsSpec extends AsyncHmrcSpec {

  trait Setup {

    def applicationsWithRateLimit(rateLimit: Option[RateLimitTier], numberOfApplications: Int): List[StoredApplication] = {
      def mockedApplication: StoredApplication = {
        val application: StoredApplication = mock[StoredApplication]
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
      private val numberOfBronze  = 10
      private val numberOfSilver  = 5
      private val numberOfGold    = 2
      private val numberOfUnknown = 1

      private val applicationsToReturn: List[StoredApplication] =
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
