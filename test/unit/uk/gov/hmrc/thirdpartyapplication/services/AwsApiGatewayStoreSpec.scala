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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import akka.actor.ActorSystem
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.AwsApiGatewayConnector
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.AwsApiGatewayStore
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.Future.successful
import scala.util.Random.nextString

class AwsApiGatewayStoreSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  implicit val actorSystem: ActorSystem = ActorSystem("test")

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val mockAwsApiGatewayConnector: AwsApiGatewayConnector = mock[AwsApiGatewayConnector]
    val underTest = new AwsApiGatewayStore(mockAwsApiGatewayConnector)

    val applicationName = "myapplication"
    val serverToken: String = nextString(2)
    val app = ApplicationData(
      UUID.randomUUID(),
      "MyApp",
      "myapp",
      Set.empty,
      Some("description"),
      "myuser",
      "mypassword",
      applicationName,
      ApplicationTokens(
        EnvironmentToken(nextString(2), nextString(2), serverToken)),
      testingState(),
      createdOn = DateTimeUtils.now,
      lastAccess = Some(DateTimeUtils.now))
  }

  "createApplication" should {
    "create an application in AWS and generate token" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(eqTo(applicationName), *, eqTo(BRONZE))(eqTo(hc)))
        .thenReturn(successful(HasSucceeded))

      val result: EnvironmentToken = await(underTest.createApplication("myuser", "mypassword", applicationName))

      result.clientId should have length 28
      result.wso2ClientSecret should have length 0
      result.accessToken should have length 32
      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(eqTo(applicationName), *, eqTo(BRONZE))(eqTo(hc))
    }
  }

  "updateApplication" should {
    "update rate limiting tier in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(applicationName, serverToken, SILVER)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest updateApplication(app, SILVER))

      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(applicationName, serverToken, SILVER)(hc)
    }

  }

  "deleteApplication" should {
    "delete an application in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.deleteApplication(applicationName)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest.deleteApplication("myuser", "mypassword", applicationName))

      verify(mockAwsApiGatewayConnector).deleteApplication(applicationName)(hc)
    }

  }

  "addSubscription" should {
    "return HasSucceeded" in new Setup {
      val result: HasSucceeded = await(underTest.addSubscription(app, APIIdentifier("some/context", "1.0")))

      result shouldBe HasSucceeded
      verifyZeroInteractions(mockAwsApiGatewayConnector)
    }
  }

  "removeSubscription" should {
    "return HasSucceeded" in new Setup {
      val result: HasSucceeded = await(underTest.removeSubscription(app, APIIdentifier("some/context", "1.0")))

      result shouldBe HasSucceeded
      verifyZeroInteractions(mockAwsApiGatewayConnector)
    }

  }

  "resubscribeApi" should {
    "return HasSucceeded" in new Setup {
      val api = APIIdentifier("some/context", "1.0")
      val anotherApi = APIIdentifier("some/context_2", "1.0")

      val result: HasSucceeded = await(underTest.resubscribeApi(Seq(api, anotherApi), app.wso2Username, app.wso2Password, applicationName, api, SILVER))

      result shouldBe HasSucceeded
      verifyZeroInteractions(mockAwsApiGatewayConnector)
    }
  }

  "getSubscriptions" should {
    "return an empty sequence" in new Setup {
      val result: Seq[APIIdentifier] = await(underTest.getSubscriptions(app.wso2Username, app.wso2Password, applicationName))

      result shouldBe empty
      verifyZeroInteractions(mockAwsApiGatewayConnector)
    }
  }

  "checkApplicationRateLimitTier" should {
    "return HasSucceeded" in new Setup {
      val result: HasSucceeded = await(underTest.checkApplicationRateLimitTier(app.wso2Username, app.wso2Password, applicationName, SILVER))

      result shouldBe HasSucceeded
      verifyZeroInteractions(mockAwsApiGatewayConnector)
    }
  }
}
