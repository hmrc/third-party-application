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

package uk.gov.hmrc.thirdpartyapplication.connector

import java.util.UUID

import com.codahale.metrics.SharedMetricRegistries
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.ContentTypes.JSON
import play.api.http.HeaderNames.{AUTHORIZATION, CONTENT_TYPE}
import play.api.http.Status.{ACCEPTED, INTERNAL_SERVER_ERROR, OK}
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier}
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.SILVER
import uk.gov.hmrc.thirdpartyapplication.models.{HasSucceeded, RateLimitTier}

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.http.UpstreamErrorResponse

class AwsApiGatewayConnectorSpec
  extends ConnectorSpec
    with GuiceOneAppPerSuite
    with BeforeAndAfterAll {

  private val stubPort = sys.env.getOrElse("WIREMOCK", "22221").toInt
  private val stubHost = "localhost"
  private val wireMockUrl = s"http://$stubHost:$stubPort"
  private val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  private val applicationName = "api-platform-app"
  private val requestedUsagePlan: RateLimitTier.Value = SILVER
  private val apiKeyValue: String = UUID.randomUUID().toString

  trait Setup {
    SharedMetricRegistries.clear()
    WireMock.reset()
    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization("foo")))

    val expectedUpdateURL: String = s"/v1/usage-plans/$requestedUsagePlan/api-keys"
    val expectedRequest: UpdateApplicationUsagePlanRequest = UpdateApplicationUsagePlanRequest(applicationName, apiKeyValue)

    val expectedDeleteURL: String = s"/v1/api-keys/$applicationName"

    val http: HttpClient = fakeApplication.injector.instanceOf[HttpClient]
    val awsApiKey: String = UUID.randomUUID().toString
    val config: AwsApiGatewayConfig = AwsApiGatewayConfig(wireMockUrl, awsApiKey)

    val underTest: AwsApiGatewayConnector = new AwsApiGatewayConnector(http, config)
  }

  override def beforeAll() {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
  }

  override def afterAll() {
    wireMockServer.stop()
  }

  "createOrUpdateApplication" should {
    "send the right body and headers when creating or updating an application" in new Setup {
      stubFor(post(urlPathEqualTo(expectedUpdateURL))
        .willReturn(
          aResponse()
            .withStatus(ACCEPTED)
            .withBody(s"""{ "RequestId" : "${UUID.randomUUID().toString}" }""")))

      await(underTest.createOrUpdateApplication(applicationName, apiKeyValue, SILVER)(hc))

      wireMockServer.verify(postRequestedFor(urlEqualTo(expectedUpdateURL))
        .withHeader(CONTENT_TYPE, equalTo(JSON))
        .withHeader("x-api-key", equalTo(awsApiKey))
        .withoutHeader(AUTHORIZATION)
        .withRequestBody(equalToJson(Json.toJson(expectedRequest).toString())))
    }

    "return Upstream5xxResponse when application creation or update fails" in new Setup {
      stubFor(post(urlPathEqualTo(expectedUpdateURL))
        .willReturn(
          aResponse()
            .withStatus(INTERNAL_SERVER_ERROR)))

      intercept[UpstreamErrorResponse] {
        await(underTest.createOrUpdateApplication(applicationName, apiKeyValue, SILVER)(hc)) shouldBe HasSucceeded
      }.statusCode shouldBe INTERNAL_SERVER_ERROR

    }
  }

  "deleteApplication" should {
    "send the x-api-key header when deleting an application" in new Setup {
      stubFor(delete(urlPathEqualTo(expectedDeleteURL))
        .willReturn(
          aResponse()
            .withStatus(OK)
            .withBody(s"""{ "RequestId" : "${UUID.randomUUID().toString}" }""")))

      await(underTest.deleteApplication(applicationName)(hc))

      wireMockServer.verify(deleteRequestedFor(urlEqualTo(expectedDeleteURL))
        .withHeader("x-api-key", equalTo(awsApiKey))
        .withoutHeader(AUTHORIZATION))
    }

    "return Upstream5xxResponse when application deletion fails" in new Setup {
      stubFor(delete(urlPathEqualTo(expectedDeleteURL))
        .willReturn(
          aResponse()
            .withStatus(INTERNAL_SERVER_ERROR)))

      intercept[UpstreamErrorResponse] {
        await(underTest.deleteApplication(applicationName)(hc)) shouldBe HasSucceeded
      }.statusCode shouldBe INTERNAL_SERVER_ERROR
    }
  }
}
