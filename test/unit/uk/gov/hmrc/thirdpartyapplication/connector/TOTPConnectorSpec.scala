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

package unit.uk.gov.hmrc.thirdpartyapplication.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.scalatest.BeforeAndAfterEach
import play.api.http.ContentTypes.JSON
import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.http.Status.{CREATED, INTERNAL_SERVER_ERROR}
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.connector.TOTPConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.models.TOTP
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.{USER_AGENT, X_REQUEST_ID_HEADER}
import scala.concurrent.ExecutionContext.Implicits.global

class TOTPConnectorSpec extends UnitSpec with BeforeAndAfterEach with WithFakeApplication {

  val stubPort = sys.env.getOrElse("WIREMOCK", "21213").toInt
  val stubHost = "localhost"
  val wireMockUrl = s"http://$stubHost:$stubPort"
  val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    val underTest = new TOTPConnector {
      override val serviceUrl: String = wireMockUrl
      override lazy val applicationName: String = "third-party-application"
    }
  }

  override def beforeEach() {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
  }

  override def afterEach() {
    wireMockServer.resetMappings()
    wireMockServer.stop()
  }

  "generateTotp" should {
    val totpId = "clientId"
    val totpSecret = "aTotp"

    val applicationName = "third-party-application"

    "return the TOTP when it is successfully created" in new Setup {

      stubFor(post(urlPathMatching("/time-based-one-time-password/secret"))
        .withHeader(USER_AGENT, equalTo(applicationName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .willReturn(
          aResponse()
            .withStatus(CREATED)
            .withHeader(CONTENT_TYPE, JSON)
            .withBody(
              Json.obj("secret" -> totpSecret, "id" -> totpId).toString()
            )
        )
      )

      val result = await(underTest.generateTotp())

      result shouldBe TOTP(totpSecret, totpId)
    }

    "fail when the TOTP creation fails" in new Setup {

      stubFor(post(urlPathMatching("/time-based-one-time-password/secret"))
        .withHeader(USER_AGENT, equalTo(applicationName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .willReturn(
          aResponse()
            .withStatus(INTERNAL_SERVER_ERROR)
            .withHeader(CONTENT_TYPE, JSON)))

      intercept[RuntimeException] {
        await(underTest.generateTotp())
      }
    }
  }
}
