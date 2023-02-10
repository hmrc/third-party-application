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

package uk.gov.hmrc.thirdpartyapplication.services

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful
import scala.util.Random.nextString

import akka.actor.ActorSystem
import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier._
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.ApplicationDeleted
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

class AwsApiGatewayStoreSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  implicit val actorSystem: ActorSystem = ActorSystem("test")

  trait Setup {
    implicit val hc: HeaderCarrier                         = HeaderCarrier()
    val mockAwsApiGatewayConnector: AwsApiGatewayConnector = mock[AwsApiGatewayConnector]
    val underTest                                          = new AwsApiGatewayStore(mockAwsApiGatewayConnector)

    val applicationName     = "myapplication"
    val serverToken: String = nextString(2)

    val app = ApplicationData(
      ApplicationId.random,
      "MyApp",
      "myapp",
      Set.empty,
      Some("description"),
      applicationName,
      ApplicationTokens(
        Token(ClientId.random, serverToken)
      ),
      testingState(),
      createdOn = FixedClock.now,
      lastAccess = Some(FixedClock.now)
    )
  }

  "createApplication" should {
    "create an application in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(eqTo(applicationName), *, eqTo(BRONZE))(eqTo(hc)))
        .thenReturn(successful(HasSucceeded))

      await(underTest.createApplication(applicationName, serverToken))

      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(eqTo(applicationName), *, eqTo(BRONZE))(eqTo(hc))
    }
  }

  "updateApplication" should {
    "update rate limiting tier in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(applicationName, serverToken, SILVER)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest updateApplication (app, SILVER))

      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(applicationName, serverToken, SILVER)(hc)
    }

  }

  "deleteApplication" should {
    "delete an application in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.deleteApplication(applicationName)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest.deleteApplication(applicationName))

      verify(mockAwsApiGatewayConnector).deleteApplication(applicationName)(hc)
    }
  }

  "applyEvents" should {
    val now = FixedClock.now

    def buildApplicationDeletedEvent(applicationId: ApplicationId) =
      ApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        now,
        Actors.Collaborator("requester@example.com"),
        ClientId("clientId"),
        "wso2ApplicationName",
        "reasons"
      )

    "handle an ApplicationDeleted event by calling the connector" in new Setup {
      val applicationId1 = ApplicationId.random

      when(mockAwsApiGatewayConnector.deleteApplication("wso2ApplicationName")(hc)).thenReturn(successful(HasSucceeded))

      val event = buildApplicationDeletedEvent(applicationId1)

      val result = await(underTest.applyEvents(NonEmptyList.one(event)))

      result shouldBe Some(HasSucceeded)
      verify(mockAwsApiGatewayConnector).deleteApplication("wso2ApplicationName")(hc)
    }
  }
}
