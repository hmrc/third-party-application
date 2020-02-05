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

package it.uk.gov.hmrc.thirdpartyapplication.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.joda.time.DateTime
import org.scalatest.BeforeAndAfterAll
import play.api.http.ContentTypes.JSON
import play.api.http.HeaderNames.{AUTHORIZATION, CONTENT_TYPE}
import play.api.http.Status.{BAD_REQUEST, OK}
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.connector.{FetchUsersByEmailAddressesRequest, ThirdPartyDeveloperConfig, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.UserResponse

import scala.concurrent.ExecutionContext.Implicits.global

class ThirdPartyDeveloperConnectorSpec extends UnitSpec with WithFakeApplication with BeforeAndAfterAll {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val stubPort = sys.env.getOrElse("WIREMOCK", "22221").toInt
  private val stubHost = "localhost"
  private val wireMockUrl = s"http://$stubHost:$stubPort"
  private val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  private val GetByEmailsUriPath: String = "/developers/get-by-emails"

  trait Setup {
    WireMock.reset()

    val underTest = new ThirdPartyDeveloperConnector(fakeApplication.injector.instanceOf[HttpClient], ThirdPartyDeveloperConfig(wireMockUrl))
  }

  override def beforeAll() {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
  }

  override def afterAll() {
    wireMockServer.stop()
  }

  "fetchUsersByEmailAddresses" should {

    "make appropriate HTTP call to retrieve user details" in new Setup {
      val user1: UserResponse = UserResponse("foo@bar.com", "Joe", "Bloggs", DateTime.now, DateTime.now)
      val user2: UserResponse = UserResponse("bar@baz.com", "John", "Doe", DateTime.now, DateTime.now)

      val userEmails: Set[String] = Set(user1.email, user2.email)

      stubFor(post(urlPathEqualTo(GetByEmailsUriPath))
        .willReturn(
          aResponse()
            .withStatus(OK)
            .withBody(Json.toJson(Seq(user1, user2)).toString)))

      val results: Seq[UserResponse] = await(underTest.fetchUsersByEmailAddresses(userEmails))

      results.size should be (2)
      results should contain (user1)
      results should contain (user2)

      wireMockServer.verify(postRequestedFor(urlEqualTo(GetByEmailsUriPath))
        .withHeader(CONTENT_TYPE, equalTo(JSON))
        .withoutHeader(AUTHORIZATION)
        .withRequestBody(equalToJson(Json.toJson(FetchUsersByEmailAddressesRequest(userEmails)).toString)))
    }

    "throw a RuntimeException is call to TPD fails" in  new Setup {
      stubFor(post(urlPathEqualTo(GetByEmailsUriPath)).willReturn(aResponse().withStatus(BAD_REQUEST)))

      assertThrows[RuntimeException] {
        await(underTest.fetchUsersByEmailAddresses(Set("foo@bar.com")))
      }
    }
  }
}
