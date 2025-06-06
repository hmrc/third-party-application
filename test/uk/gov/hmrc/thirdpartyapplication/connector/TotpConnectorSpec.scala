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

package uk.gov.hmrc.thirdpartyapplication.connector

import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._

import play.api.http.Status._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support

import uk.gov.hmrc.apiplatform.modules.common.connectors.ConnectorSpec
import uk.gov.hmrc.thirdpartyapplication.models.Totp
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class TotpConnectorSpec extends ConnectorSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val baseUrl            = wireMockUrl

  trait Setup extends HttpClientV2Support {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val applicationName: String = "third-party-application"
    val config                  = TotpConnector.Config(baseUrl)
    val underTest               = new TotpConnector(httpClientV2, config)
  }

  "generateTotp" should {
    val totpId     = "clientId"
    val totpSecret = "aTotp"

    "return the Totp when it is successfully created" in new Setup {

      stubFor(
        post(urlEqualTo("/time-based-one-time-password/secret"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(Totp(totpSecret, totpId))
          )
      )

      val result = await(underTest.generateTotp())

      result shouldBe Totp(totpSecret, totpId)
    }

    "fail when the Totp creation fails" in new Setup {
      stubFor(
        post(urlEqualTo("/time-based-one-time-password/secret"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      intercept[RuntimeException] {
        await(underTest.generateTotp())
      }
    }
  }
}
