/*
 * Copyright 2018 HM Revenue & Customs
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

package unit.connector

import java.util.UUID

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import common.uk.gov.hmrc.common.LogSuppressing
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status
import uk.gov.hmrc.connector.APIDefinitionConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.models.JsonFormatters._
import uk.gov.hmrc.models.{APIDefinition, APIStatus, APIVersion}
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global

class APIDefinitionConnectorSpec extends UnitSpec with WithFakeApplication with MockitoSugar with ScalaFutures
  with BeforeAndAfterEach with LogSuppressing {

  implicit val hc = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
  val stubPort = sys.env.getOrElse("WIREMOCK", "21212").toInt
  val stubHost = "localhost"
  val wireMockUrl = s"http://$stubHost:$stubPort"
  val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  val apiDefinitionWithStableStatus = APIDefinition("api-service", "api-name", "api-context",
    Seq(APIVersion("1.0", APIStatus.STABLE, None)), Some(false))

  val apiDefinitionWithBetaStatus = APIDefinition("api-service", "api-name", "api-context",
    Seq(APIVersion("1.0", APIStatus.BETA, None)), Some(false))

  val apiDefinitionWithIsTestSupportFlag = APIDefinition("api-service", "api-name", "api-context",
    Seq(APIVersion("1.0", APIStatus.STABLE, None)), Some(false), Some(true))

  val apiDefinitionWithStableStatusJson =
    """[{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "STABLE"
      |    }
      |  ],
      |  "requiresTrust": false
      |}]""".stripMargin

  val apiDefinitionWithPublishedStatusJson =
    """[{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PUBLISHED"
      |    }
      |  ],
      |  "requiresTrust": false
      |}]""".stripMargin

  val apiDefinitionWithPrototypedStatusJson =
    """[{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PROTOTYPED"
      |    }
      |  ],
      |  "requiresTrust": false
      |}]""".stripMargin

  val apiDefinitionWithIsTestSupportFlagJson =
    """[{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PUBLISHED"
      |    }
      |  ],
      |  "requiresTrust": false,
      |  "isTestSupport": true
      |}]""".stripMargin

  trait Setup {
    val underTest = new APIDefinitionConnector {
      override lazy val serviceUrl: String = wireMockUrl
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

  "fetchAPIs" should {

    val applicationId: UUID = UUID.randomUUID()
    val applicationName = "third-party-application"

    "return the APIs available for an application" in new Setup {

      stubFor(get(urlPathMatching("/api-definition"))
        .withHeader(USER_AGENT, equalTo(applicationName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withQueryParam("applicationId", equalTo(applicationId.toString)).willReturn(
        aResponse()
          .withStatus(Status.OK)
          .withHeader("Content-Type", "application/json")
          .withBody(apiDefinitionWithStableStatusJson)))

      val result = await(underTest.fetchAllAPIs(applicationId))

      result shouldBe Seq(apiDefinitionWithStableStatus)
    }

    "map a status of PROTOTYPED in the JSON to BETA in the model" in new Setup {

      stubFor(get(urlPathMatching("/api-definition"))
        .withHeader(USER_AGENT, equalTo(applicationName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withQueryParam("applicationId", equalTo(applicationId.toString)).willReturn(
        aResponse()
          .withStatus(200)
          .withHeader("Content-Type", "application/json")
          .withBody(apiDefinitionWithPrototypedStatusJson)))

      val result = await(underTest.fetchAllAPIs(applicationId))

      result shouldBe Seq(apiDefinitionWithBetaStatus)
    }

    "map a status of PUBLISHED in the JSON to STABLE in the model" in new Setup {

      stubFor(get(urlPathMatching("/api-definition"))
        .withHeader(USER_AGENT, equalTo(applicationName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withQueryParam("applicationId", equalTo(applicationId.toString)).willReturn(
        aResponse()
          .withStatus(200)
          .withHeader("Content-Type", "application/json")
          .withBody(apiDefinitionWithPublishedStatusJson)))

      val result = await(underTest.fetchAllAPIs(applicationId))

      result shouldBe Seq(apiDefinitionWithStableStatus)
    }

    "map a isTestSupport flag when set in the JSON to correct value in model" in new Setup {

      stubFor(get(urlPathMatching("/api-definition"))
        .withHeader(USER_AGENT, equalTo(applicationName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withQueryParam("applicationId", equalTo(applicationId.toString)).willReturn(
        aResponse()
          .withStatus(200)
          .withHeader("Content-Type", "application/json")
          .withBody(apiDefinitionWithIsTestSupportFlagJson)))

      val result = await(underTest.fetchAllAPIs(applicationId))

      result shouldBe Seq(apiDefinitionWithIsTestSupportFlag)
    }

    "fail when api-definition returns a 500" in new Setup {

      stubFor(get(urlPathMatching("/api-definition"))
        .withHeader(USER_AGENT, equalTo(applicationName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withQueryParam("applicationId", equalTo(applicationId.toString))
        .willReturn(aResponse().withStatus(500)))

      intercept[RuntimeException] {
        await(underTest.fetchAllAPIs(applicationId))
      }
    }

  }
}