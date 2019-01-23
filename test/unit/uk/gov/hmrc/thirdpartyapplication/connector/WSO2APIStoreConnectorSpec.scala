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
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status
import uk.gov.hmrc.thirdpartyapplication.connector.WSO2APIStoreConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.Await
import scala.concurrent.duration._

class WSO2APIStoreConnectorSpec extends UnitSpec with WithFakeApplication with MockitoSugar
  with ScalaFutures with BeforeAndAfterEach {

  implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
  val stubPort = sys.env.getOrElse("WIREMOCK", "22222").toInt
  val stubHost = "localhost"
  val wireMockUrl = s"http://$stubHost:$stubPort"
  val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  trait Setup {
    val serviceName = "third-party-application"
    val underTest = new WSO2APIStoreConnector {
      override val adminUsername = "admin"
      override val serviceUrl = s"$wireMockUrl/store/site/blocks"
      override lazy val applicationName: String = "third-party-application"
    }

  }

  override def beforeEach() {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
  }

  override def afterEach() {
    wireMockServer.stop()
  }

  "login" should {

    "log the user into WSO2 API Store and return the cookies" in new Setup {

      stubFor(post(urlEqualTo("/store/site/blocks/user/login/ajax/login.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo("action=login&username=admin&password=admin"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody( """{"error":false}""")
            .withHeader("Set-Cookie", "JSESSIONID=12345")
            .withHeader("Set-Cookie", "api-store=loadbalancercookie")))

      val result = Await.result(underTest.login("admin", "admin"), 1.second)

      result shouldBe "JSESSIONID=12345;api-store=loadbalancercookie"

    }
  }

  "getSubscriptions" should {

    "get API subscriptions for a given application" in new Setup {

      val cookie = "login-cookie-123"
      val applicationName = "myapp"

      stubFor(post(urlEqualTo("/store/site/blocks/subscription/subscription-list/ajax/subscription-list.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(s"action=getSubscriptionByApplication&app=$applicationName"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody(
              """{
                |  "error":false,
                |  "apis":[
                |    {"apiName":"some--context--1.0","apiVersion":"1.0"},
                |    {"apiName":"some--context--1.0","apiVersion":"1.1"}
                |  ]
                |}"""
                .stripMargin)))

      val result = Await.result(underTest.getSubscriptions(cookie, applicationName), 1.second)

      result.seq.head.name shouldBe "some--context--1.0"
      result.seq.head.version shouldBe "1.0"
      result.seq(1).name shouldBe "some--context--1.0"
      result.seq(1).version shouldBe "1.1"

    }
  }

  "getAllSubscriptions" should {

    "get all API subscriptions" in new Setup {

      val cookie = "login-cookie-123"

      stubFor(post(urlEqualTo("/store/site/blocks/subscription/subscription-list/ajax/subscription-list.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(s"action=getAllSubscriptions"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody(
              """{
                |  "error": false,
                |  "subscriptions": {
                |    "applications": [
                |      {
                |        "id": 1,
                |        "name": "DefaultApplication",
                |        "callbackUrl": null,
                |        "prodKey": null,
                |        "prodKeyScope": null,
                |        "prodKeyScopeValue": null,
                |        "prodConsumerKey": null,
                |        "prodConsumerSecret": null,
                |        "prodRegenarateOption": true,
                |        "prodAuthorizedDomains": null,
                |        "prodValidityTime": 3600,
                |        "prodJsonString": null,
                |        "sandboxKey": null,
                |        "sandKeyScope": null,
                |        "sandKeyScopeValue": null,
                |        "sandboxConsumerKey": null,
                |        "sandboxConsumerSecret": null,
                |        "sandRegenarateOption": true,
                |        "sandboxAuthorizedDomains": null,
                |        "sandboxJsonString": null,
                |        "sandValidityTime": 3600,
                |        "subscriptions": [
                |          {
                |            "name": "pizzashack--1.0.0",
                |            "provider": "admin",
                |            "version": "1.0.0",
                |            "status": "PUBLISHED",
                |            "tier": "Unlimited",
                |            "subStatus": "UNBLOCKED",
                |            "thumburl": "/registry/resource/_system/governance/apimgt/applicationdata/icons/admin/PizzaShackAPI/1.0.0/icon",
                |            "context": "/pizzashack/1.0.0",
                |            "businessOwner": "Jane Roe",
                |            "prodKey": null,
                |            "prodConsumerKey": null,
                |            "prodConsumerSecret": null,
                |            "prodAuthorizedDomains": null,
                |            "prodValidityTime": 3600000,
                |            "sandboxKey": null,
                |            "sandboxConsumerKey": null,
                |            "sandboxConsumerSecret": null,
                |            "sandAuthorizedDomains": null,
                |            "sandValidityTime": 3600000,
                |            "hasMultipleEndpoints": "false"
                |          },
                |          {
                |            "name": "another-sample--v1.0",
                |            "provider": "admin",
                |            "version": "v1.0",
                |            "status": "PUBLISHED",
                |            "tier": "Unlimited",
                |            "subStatus": "UNBLOCKED",
                |            "thumburl": null,
                |            "context": "/another-sample/v1.0",
                |            "businessOwner": null,
                |            "prodKey": null,
                |            "prodConsumerKey": null,
                |            "prodConsumerSecret": null,
                |            "prodAuthorizedDomains": null,
                |            "prodValidityTime": 3600000,
                |            "sandboxKey": null,
                |            "sandboxConsumerKey": null,
                |            "sandboxConsumerSecret": null,
                |            "sandAuthorizedDomains": null,
                |            "sandValidityTime": 3600000,
                |            "hasMultipleEndpoints": "false"
                |          }
                |        ],
                |        "scopes": []
                |      },
                |      {
                |        "id": 2,
                |        "name": "AnotherApplication",
                |        "callbackUrl": null,
                |        "prodKey": null,
                |        "prodKeyScope": null,
                |        "prodKeyScopeValue": null,
                |        "prodConsumerKey": null,
                |        "prodConsumerSecret": null,
                |        "prodRegenarateOption": true,
                |        "prodAuthorizedDomains": null,
                |        "prodValidityTime": 3600,
                |        "prodJsonString": null,
                |        "sandboxKey": null,
                |        "sandKeyScope": null,
                |        "sandKeyScopeValue": null,
                |        "sandboxConsumerKey": null,
                |        "sandboxConsumerSecret": null,
                |        "sandRegenarateOption": true,
                |        "sandboxAuthorizedDomains": null,
                |        "sandboxJsonString": null,
                |        "sandValidityTime": 3600,
                |        "subscriptions": [
                |          {
                |            "name": "another-sample--v1.0",
                |            "provider": "admin",
                |            "version": "v1.0",
                |            "status": "PUBLISHED",
                |            "tier": "Unlimited",
                |            "subStatus": "UNBLOCKED",
                |            "thumburl": null,
                |            "context": "/another-sample/v1.0",
                |            "businessOwner": null,
                |            "prodKey": null,
                |            "prodConsumerKey": null,
                |            "prodConsumerSecret": null,
                |            "prodAuthorizedDomains": null,
                |            "prodValidityTime": 3600000,
                |            "sandboxKey": null,
                |            "sandboxConsumerKey": null,
                |            "sandboxConsumerSecret": null,
                |            "sandAuthorizedDomains": null,
                |            "sandValidityTime": 3600000,
                |            "hasMultipleEndpoints": "false"
                |          }
                |        ],
                |        "scopes": []
                |      }
                |    ],
                |    "totalLength": 1
                |  }
                |}
                |""".stripMargin)))

      val result = Await.result(underTest.getAllSubscriptions(cookie), 1.second)

      result shouldBe Map(
        "DefaultApplication" -> Seq(WSO2API("pizzashack--1.0.0", "1.0.0"), WSO2API("another-sample--v1.0", "v1.0")),
        "AnotherApplication" -> Seq(WSO2API("another-sample--v1.0", "v1.0")))
    }
  }

  "logout" should {

    "logout of WSO2 API Store for the given cookie" in new Setup {

      val cookie = "login-cookie-123"

      stubFor(get(urlEqualTo("/store/site/blocks/user/login/ajax/login.jag?action=logout"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody( s"""{"error":false}""")))

      val result = Await.result(underTest.logout(cookie), 1.second)

      result shouldBe HasSucceeded

    }

  }

  "addSubscription" should {

    "add an API subscription from WSO2 for the given application" in new Setup {
      val cookie = "login-cookie-123"
      val applicationName = "myapp"
      val api = WSO2API("my--api--1.0", "1.0")
      val adminUsername = "admin"

      stubFor(post(urlEqualTo("/store/site/blocks/subscription/subscription-add/ajax/subscription-add.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(
          s"action=addAPISubscription&name=${api.name}" +
            s"&version=${api.version}" +
            s"&provider=$adminUsername" +
            s"&tier=BRONZE_SUBSCRIPTION" +
            s"&applicationName=$applicationName"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody( s"""{"error":false}""")))

      val result = Await.result(underTest.addSubscription(cookie, applicationName, api, None, 0), 1.second)

      result shouldBe HasSucceeded
    }

  }


  "removeSubscription" should {

    "remove an API subscription from WSO2 for the given application" in new Setup {

      val cookie = "login-cookie-123"
      val applicationName = "myapp"
      val api = WSO2API("my--api--1.0", "1.0")
      val adminUsername = "admin"

      stubFor(post(urlEqualTo("/store/site/blocks/subscription/subscription-remove/ajax/subscription-remove.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(s"action=removeSubscription&name=${api.name}&version=${api.version}&provider=$adminUsername&applicationName=$applicationName"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody( s"""{"error":false}""")))

      val result = Await.result(underTest.removeSubscription(cookie, applicationName, api, 0), 1.second)
      result shouldBe HasSucceeded
    }

  }

  "generateApplicationKey" should {

    "generate an application key in WSO2 for a given application name and key type" in new Setup {

      val cookie = "login-cookie-123"
      val applicationName = "myapp"
      val environment = Environment.PRODUCTION

      stubFor(post(urlEqualTo("/store/site/blocks/subscription/subscription-add/ajax/subscription-add.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(
          s"action=generateApplicationKey&application=$applicationName&keytype=$environment&callbackUrl=&authorizedDomains=ALL&validityTime=-1"))
        .willReturn(aResponse().withStatus(Status.OK)
          .withHeader(CONTENT_TYPE, "application/json")
          .withBody( s"""{"error":false,"data":{"key":{"consumerSecret":"secret","consumerKey":"key","accessToken":"token"}}}""")))

      val result = Await.result(underTest.generateApplicationKey(cookie, applicationName, environment), 1.second)

      result shouldBe (_: EnvironmentToken)

    }

  }

  "deleteApplication" should {

    "delete an application in WSO2 for the given application name" in new Setup {

      val cookie = "login-cookie-123"
      val applicationName = "myapp"

      stubFor(post(urlEqualTo("/store/site/blocks/application/application-remove/ajax/application-remove.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(s"action=removeApplication&application=$applicationName"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody( s"""{"error":false}""")))

      val result = Await.result(underTest.deleteApplication(cookie, applicationName), 1.second)

      result shouldBe HasSucceeded

    }

  }

  "createApplication" should {

    "create an application in WSO2 for the given application name" in new Setup {

      val cookie = "login-cookie-123"
      val applicationName = "myapp"

      stubFor(post(urlEqualTo("/store/site/blocks/application/application-add/ajax/application-add.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(COOKIE, equalTo(cookie))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(s"action=addApplication&application=$applicationName&tier=BRONZE_APPLICATION&description=&callbackUrl="))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody( s"""{"error":false}""")))

      val result = Await.result(underTest.createApplication(cookie, applicationName), 1.second)

      result shouldBe HasSucceeded

    }

  }

  "updateApplication" should {

    val cookie = "login-cookie-123"
    val wso2ApplicationName = "myapp"

    def stubForApplicationUpdate(userAgent: String, responseCode: Int, isError: Option[Boolean] = None): Unit = {

      val body = isError match {
        case Some(b) => s"""{"error":${b.toString}}"""
        case _ => s"""{}"""
      }

      stubFor(post(urlEqualTo("/store/site/blocks/application/application-update/ajax/application-update.jag"))
        .withHeader(USER_AGENT, equalTo(userAgent))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(
          s"action=updateApplication" +
            s"&applicationOld=$wso2ApplicationName" +
            s"&applicationNew=$wso2ApplicationName" +
            s"&callbackUrlNew=" +
            s"&descriptionNew=" +
            s"&tier=SILVER_APPLICATION"))
        .willReturn(
          aResponse()
            .withStatus(responseCode)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody(body)))
    }

    "update rate limiting tier is wso2" in new Setup {
      stubForApplicationUpdate(serviceName, Status.OK, isError = Some(false))

      await(underTest updateApplication(cookie, wso2ApplicationName, RateLimitTier.SILVER)) shouldBe HasSucceeded
    }

    "thrown an exception if the response contains an error" in new Setup {
      stubForApplicationUpdate(serviceName, Status.OK, isError = Some(true))

      intercept[RuntimeException] {
        await(underTest updateApplication(cookie, wso2ApplicationName, RateLimitTier.SILVER))
      }
    }

    "thrown an exception if the response code is not 200 OK" in new Setup {
      stubForApplicationUpdate(serviceName, Status.INTERNAL_SERVER_ERROR)

      intercept[RuntimeException] {
        await(underTest updateApplication(cookie, wso2ApplicationName, RateLimitTier.SILVER))
      }
    }
  }

  "createUser" should {

    "create a user in WSO2 for the given username and password" in new Setup {

      val username = "myuser"
      val password = "mypassword"
      val applicationName = "myapp"

      stubFor(post(urlEqualTo("/store/site/blocks/user/sign-up/ajax/user-add.jag"))
        .withHeader(CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
        .withHeader(USER_AGENT, equalTo(serviceName))
        .withHeader(X_REQUEST_ID_HEADER, equalTo("requestId"))
        .withRequestBody(equalTo(s"action=addUser&username=$username&password=$password&allFieldsValues=firstname|lastname|email"))
        .willReturn(
          aResponse()
            .withStatus(Status.OK)
            .withHeader(CONTENT_TYPE, "application/json")
            .withBody( s"""{"error":false}""")))

      val result = Await.result(underTest.createUser(username, password), 1.second)

      result shouldBe HasSucceeded

    }

  }

}