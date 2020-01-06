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

package unit.uk.gov.hmrc.thirdpartyapplication.metrics

import org.mockito.Mockito.when
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.metrics.ApplicationCount
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApplicationCountSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar {

  trait Setup {
    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]

    val metricUnderTest: ApplicationCount = new ApplicationCount(mockApplicationRepository)
  }

  "metrics refresh" should {
    "output the application count correctly" in new Setup {
      private val numberOfApplications: Int = 10
      when(mockApplicationRepository.count).thenReturn(Future.successful(numberOfApplications))

      private val result: Map[String, Int] = await(metricUnderTest.metrics)

      result("applicationCount") shouldBe numberOfApplications
    }
  }
}
