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

import play.api.http.Status.{CREATED, INTERNAL_SERVER_ERROR}
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, Upstream5xxResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.connector.{TotpConfig, TotpConnector}
import uk.gov.hmrc.thirdpartyapplication.models.Totp
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.X_REQUEST_ID_HEADER

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TotpConnectorSpec extends ConnectorSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val baseUrl = s"http://example.com"

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    val applicationName: String = "third-party-application"
    val mockHttpClient = mock[HttpClient]
    val config = TotpConfig(baseUrl)
    val underTest = new TotpConnector(mockHttpClient, config)
  }

  "generateTotp" should {
    val totpId = "clientId"
    val totpSecret = "aTotp"

    "return the Totp when it is successfully created" in new Setup {

      val responseBody = Json.obj("secret" -> totpSecret, "id" -> totpId)
      when(mockHttpClient.POSTEmpty[HttpResponse](*,*)(*, *, *))
        .thenReturn(Future(HttpResponse(CREATED, Some(responseBody))))

      val result = await(underTest.generateTotp())

      result shouldBe Totp(totpSecret, totpId)
    }

    "fail when the Totp creation fails" in new Setup {

      when(mockHttpClient.POSTEmpty[HttpResponse](*,*)(*, *, *))
        .thenReturn(Future.failed(Upstream5xxResponse("", INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR)))

      intercept[RuntimeException] {
        await(underTest.generateTotp())
      }
    }
  }
}
