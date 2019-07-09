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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.mockito.Matchers.{any, anyInt, anyString}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.{AwsApiGatewayConnector, Wso2ApiStoreConnector}
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier._
import uk.gov.hmrc.thirdpartyapplication.models.{db, _}
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.services.RealApiGatewayStore
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.X_REQUEST_ID_HEADER

import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.Random.nextString

class ApiGatewayStoreSpec extends UnitSpec with ScalaFutures with MockitoSugar with ApplicationStateUtil {

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    val mockWSO2APIStoreConnector = mock[Wso2ApiStoreConnector]
    val mockAwsApiGatewayConnector = mock[AwsApiGatewayConnector]
    val mockSubscriptionRepository = mock[SubscriptionRepository]

    val underTest = new RealApiGatewayStore(mockWSO2APIStoreConnector, mockAwsApiGatewayConnector, mockSubscriptionRepository) {
      override val resubscribeMaxRetries = 0
    }

  }

  "createApplication" should {

    "create an application in AWS and WSO2 and generate production and sandbox tokens" in new Setup {
      val wso2Username = "myuser"
      val wso2Password = "mypassword"
      val wso2ApplicationName = "myapplication"
      val cookie = "some-cookie-value"
      val tokens = ApplicationTokens(EnvironmentToken("aaa", "bbb", "ccc"))

      when(mockWSO2APIStoreConnector.createUser(wso2Username, wso2Password))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password))
        .thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.createApplication(cookie, wso2ApplicationName))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.generateApplicationKey(cookie, wso2ApplicationName, Environment.PRODUCTION))
        .thenReturn(Future.successful(tokens.production))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, tokens.production.accessToken, BRONZE)(hc))
        .thenReturn(successful(HasSucceeded))

      val result: ApplicationTokens = await(underTest.createApplication(wso2Username, wso2Password, wso2ApplicationName))

      result shouldBe tokens

      verify(mockWSO2APIStoreConnector).logout(cookie)
      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(wso2ApplicationName, tokens.production.accessToken, BRONZE)(hc)
    }

  }

  "updateApplication" should {

    "update rate limiting tier in AWS and WSO2" in new Setup {
      val wso2Username = "myuser"
      val wso2Password = "mypassword"
      val wso2ApplicationName = "myapplication"
      val serverToken: String = nextString(2)
      val cookie = "some-cookie-value"
      val app = ApplicationData(
        UUID.randomUUID(),
        "MyApp",
        "myapp",
        Set.empty,
        Some("description"),
        wso2Username,
        wso2Password,
        wso2ApplicationName,
        ApplicationTokens(
          EnvironmentToken(nextString(2), nextString(2), serverToken)),
        testingState())

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.updateApplication(cookie, wso2ApplicationName, SILVER)).
        thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, serverToken, SILVER)(hc)).thenReturn(successful(HasSucceeded))
      when(mockSubscriptionRepository.getSubscriptions(app.id)).thenReturn(successful(Seq(APIIdentifier("hello", "1.0"))))

      await(underTest updateApplication(app, SILVER))

      verify(mockWSO2APIStoreConnector).updateApplication(cookie, wso2ApplicationName, SILVER)
      verify(mockWSO2APIStoreConnector).logout(cookie)
      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(wso2ApplicationName, serverToken, SILVER)(hc)
    }

  }

  "deleteApplication" should {

    "delete an application in AWS and WSO2" in new Setup {

      val wso2Username = "myuser"
      val wso2Password = "mypassword"
      val wso2ApplicationName = "myapplication"
      val cookie = "some-cookie-value"

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.deleteApplication(cookie, wso2ApplicationName))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))
      when(mockAwsApiGatewayConnector.deleteApplication(wso2ApplicationName)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest.deleteApplication(wso2Username, wso2Password, wso2ApplicationName))

      verify(mockWSO2APIStoreConnector).deleteApplication(cookie, wso2ApplicationName)
      verify(mockWSO2APIStoreConnector).logout(cookie)
      verify(mockAwsApiGatewayConnector).deleteApplication(wso2ApplicationName)(hc)
    }

  }

  "addSubscription" should {

    val wso2Username = "myuser"
    val wso2Password = "mypassword"
    val wso2ApplicationName = "myapplication"
    val cookie = "some-cookie-value"
    val wso2API = Wso2Api("some--context--1.0", "1.0")
    val api = APIIdentifier("some/context", "1.0")
    val serverToken: String = nextString(2)
    val app = db.ApplicationData(
      UUID.randomUUID(),
      "MyApp",
      "myapp",
      Set.empty,
      Some("description"),
      wso2Username,
      wso2Password,
      wso2ApplicationName,
      ApplicationTokens(
        EnvironmentToken(nextString(2), nextString(2), serverToken)),
      testingState(),
      rateLimitTier = Some(GOLD))

    "add a subscription to an application in AWS and WSO2" in new Setup {

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.addSubscription(cookie, wso2ApplicationName, wso2API, Some(GOLD), 0))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, serverToken, GOLD)(hc)).thenReturn(successful(HasSucceeded))
      when(mockSubscriptionRepository.getSubscriptions(app.id)).thenReturn(successful(Seq(APIIdentifier("hello", "1.0"))))

      await(underTest.addSubscription(app, api))

      verify(mockWSO2APIStoreConnector).addSubscription(cookie, wso2ApplicationName, wso2API, Some(GOLD), 0)
      verify(mockWSO2APIStoreConnector).logout(cookie)
      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(wso2ApplicationName, serverToken, GOLD)(hc)
    }

    "fail when add subscription fails" in new Setup {

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.addSubscription(cookie, wso2ApplicationName, wso2API, Some(GOLD), 0))
        .thenReturn(Future.failed(new RuntimeException))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))

      intercept[RuntimeException] {
        await(underTest.addSubscription(app, api))
      }
    }
  }

  "removeSubscription" should {

    "remove a subscription from an application in AWS and WSO2" in new Setup {

      val wso2Username = "myuser"
      val wso2Password = "mypassword"
      val wso2ApplicationName = "myapplication"
      val cookie = "some-cookie-value"
      val wso2API = Wso2Api("some--context--1.0", "1.0")
      val api = APIIdentifier("some/context", "1.0")
      val serverToken: String = nextString(2)
      val app = db.ApplicationData(
        UUID.randomUUID(),
        "MyApp",
        "myapp",
        Set.empty,
        Some("description"),
        wso2Username,
        wso2Password,
        wso2ApplicationName,
        ApplicationTokens(
          EnvironmentToken(nextString(2), nextString(2), serverToken)),
        testingState(),
        rateLimitTier = Some(GOLD))

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.removeSubscription(cookie, wso2ApplicationName, wso2API, 0))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, serverToken, GOLD)(hc)).thenReturn(successful(HasSucceeded))
      when(mockSubscriptionRepository.getSubscriptions(app.id)).thenReturn(successful(Seq(api, APIIdentifier("hello", "1.0"))))

      await(underTest.removeSubscription(app, api))

      verify(mockWSO2APIStoreConnector).removeSubscription(cookie, wso2ApplicationName, wso2API, 0)
      verify(mockWSO2APIStoreConnector).logout(cookie)
      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(wso2ApplicationName, serverToken, GOLD)(hc)
    }

  }

  "resubscribeApi" should {

    val wso2Username = "myuser"
    val wso2Password = "mypassword"
    val wso2ApplicationName = "myapplication"
    val cookie = "some-cookie-value"
    val wso2Api = Wso2Api("some--context--1.0", "1.0")
    val api = APIIdentifier("some/context", "1.0")
    val anotherWso2Api = Wso2Api("some--context_2--1.0", "1.0")
    val anotherApi = APIIdentifier("some/context_2", "1.0")

    "remove and then add subscriptions" in new Setup {

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))

      when(mockWSO2APIStoreConnector.removeSubscription(cookie, wso2ApplicationName, wso2Api, 0))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.addSubscription(cookie, wso2ApplicationName, wso2Api, Some(SILVER), 0))
        .thenReturn(Future.successful(HasSucceeded))

      when(mockWSO2APIStoreConnector.removeSubscription(cookie, wso2ApplicationName, anotherWso2Api, 0))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.addSubscription(cookie, wso2ApplicationName, anotherWso2Api, Some(SILVER), 0))
        .thenReturn(Future.successful(HasSucceeded))

      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))

      when(mockWSO2APIStoreConnector.getSubscriptions(cookie, wso2ApplicationName)).thenAnswer(
        new Answer[Future[Seq[Wso2Api]]] {
          var count = 0
          override def answer(invocation: InvocationOnMock): Future[Seq[Wso2Api]] = {
            count += 1
            count match {
              case 1 => Future.successful(Seq(anotherWso2Api))
              case 2 => Future.successful(Seq(wso2Api, anotherWso2Api))
              case 3 => Future.successful(Seq(wso2Api))
              case 4 => Future.successful(Seq(wso2Api, anotherWso2Api))
              case x => throw new IllegalStateException("Invocation not expected: " + x)
            }
          }
        }
      )

      await(underTest.resubscribeApi(Seq(api, anotherApi), wso2Username, wso2Password, wso2ApplicationName, api, SILVER))
      await(underTest.resubscribeApi(Seq(api, anotherApi), wso2Username, wso2Password, wso2ApplicationName, anotherApi, SILVER))

      verify(mockWSO2APIStoreConnector, times(2)).login(wso2Username, wso2Password)

      verify(mockWSO2APIStoreConnector, times(4)).getSubscriptions(cookie, wso2ApplicationName)

      verify(mockWSO2APIStoreConnector).removeSubscription(cookie, wso2ApplicationName, wso2Api, 0)
      verify(mockWSO2APIStoreConnector).addSubscription(cookie, wso2ApplicationName, wso2Api, Some(SILVER), 0)

      verify(mockWSO2APIStoreConnector).removeSubscription(cookie, wso2ApplicationName, anotherWso2Api, 0)
      verify(mockWSO2APIStoreConnector).addSubscription(cookie, wso2ApplicationName, anotherWso2Api, Some(SILVER), 0)

      verify(mockWSO2APIStoreConnector, times(2)).logout(cookie)
    }

    "fail when remove subscription fails" in new Setup {

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.removeSubscription(cookie, wso2ApplicationName, wso2Api, 0))
        .thenReturn(Future.failed(new RuntimeException))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))

      intercept[RuntimeException] {
        await(underTest.resubscribeApi(Seq(api), wso2Username, wso2Password, wso2ApplicationName, api, SILVER))
      }

      verify(mockWSO2APIStoreConnector, never()).addSubscription(anyString(), anyString(), any[Wso2Api], any[Option[RateLimitTier]], anyInt())(any[HeaderCarrier])
    }

    "fail when add subscription fails" in new Setup {

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.removeSubscription(cookie, wso2ApplicationName, wso2Api, 0))
        .thenReturn(Future.successful(HasSucceeded))
      when(mockWSO2APIStoreConnector.addSubscription(cookie, wso2ApplicationName, wso2Api, Some(SILVER), 0))
        .thenReturn(Future.failed(new RuntimeException))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))

      intercept[RuntimeException] {
        await(underTest.resubscribeApi(Seq(api), wso2Username, wso2Password, wso2ApplicationName, api, SILVER))
      }
    }
  }

  "getSubscriptions" should {

    "get subscriptions for an application from WSO2" in new Setup {

      val wso2Username = "myuser"
      val wso2Password = "mypassword"
      val wso2ApplicationName = "myapplication"
      val cookie = "some-cookie-value"
      val wso2Subscriptions = Seq(Wso2Api("some--context--1.0", "1.0"), Wso2Api("some--other--context--1.0", "1.0"))
      val subscriptions = Seq(APIIdentifier("some/context", "1.0"), APIIdentifier("some/other/context", "1.0"))

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.getSubscriptions(cookie, wso2ApplicationName))
        .thenReturn(Future.successful(wso2Subscriptions))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))

      val result = await(underTest.getSubscriptions(wso2Username, wso2Password, wso2ApplicationName))

      result shouldBe subscriptions
      verify(mockWSO2APIStoreConnector).logout(cookie)
    }

  }

  "getAllSubscriptions" should {

    "retrieve all subscriptions for all applications in WSO2" in new Setup {

      val wso2Username = "myuser"
      val wso2Password = "mypassword"
      val wso2ApplicationName = "myapplication"
      val cookie = "some-cookie-value"
      val wso2Subscriptions = Seq(Wso2Api("some--context--1.0", "1.0"), Wso2Api("some--other--context--1.0", "1.0"))
      val subscriptions = Seq(APIIdentifier("some/context", "1.0"), APIIdentifier("some/other/context", "1.0"))

      when(mockWSO2APIStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(cookie))
      when(mockWSO2APIStoreConnector.getAllSubscriptions(cookie))
        .thenReturn(Future.successful(Map(wso2ApplicationName -> wso2Subscriptions)))
      when(mockWSO2APIStoreConnector.logout(cookie)).thenReturn(Future.successful(HasSucceeded))

      val result = await(underTest.getAllSubscriptions(wso2Username, wso2Password))

      result shouldBe Map(wso2ApplicationName -> subscriptions)
      verify(mockWSO2APIStoreConnector).logout(cookie)
    }
  }
}
