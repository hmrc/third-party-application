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

package unit.uk.gov.hmrc.thirdpartyapplication.connector

import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}

import scala.concurrent.ExecutionContext.Implicits.global

class AuthConnectorSpec extends ConnectorSpec with WithFakeApplication{
  trait Setup {
    implicit val hc = HeaderCarrier()

    val mockAuthConfig = mock[AuthConfig]

    val httpClient = fakeApplication.injector.instanceOf[HttpClient]
    val connector = new AuthConnector(httpClient, mockAuthConfig)

    val url = "AUrl"

    when(mockAuthConfig.baseUrl).thenReturn(url)
  }

  "auth connector" should {

    "get the base url from the app config" in new Setup {
      val result = connector.serviceUrl
      result shouldBe url
    }
  }
}
