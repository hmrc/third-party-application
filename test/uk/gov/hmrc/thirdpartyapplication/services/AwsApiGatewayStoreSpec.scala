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

import org.apache.pekko.actor.ActorSystem

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationStateFixtures, _}
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.StoredApplicationData.storedApp

class AwsApiGatewayStoreSpec extends AsyncHmrcSpec with ApplicationStateFixtures with FixedClock {

  implicit val actorSystem: ActorSystem = ActorSystem("test")

  trait Setup {
    implicit val hc: HeaderCarrier                         = HeaderCarrier()
    val mockAwsApiGatewayConnector: AwsApiGatewayConnector = mock[AwsApiGatewayConnector]
    val underTest                                          = new AwsApiGatewayStore(mockAwsApiGatewayConnector)

    val serverToken: String = StoredApplicationData.serverToken

    val app = storedApp.copy(wso2ApplicationName = "abc123456")
  }

  "createApplication" should {
    "create an application in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(eqTo(app.wso2ApplicationName), *, eqTo(RateLimitTier.BRONZE))(eqTo(hc)))
        .thenReturn(successful(HasSucceeded))

      await(underTest.createApplication(app.wso2ApplicationName, serverToken))

      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(eqTo(app.wso2ApplicationName), *, eqTo(RateLimitTier.BRONZE))(eqTo(hc))
    }
  }

  "updateApplication" should {
    "update rate limiting tier in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(app.wso2ApplicationName, serverToken, RateLimitTier.SILVER)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest.updateApplication(app, RateLimitTier.SILVER))

      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(app.wso2ApplicationName, serverToken, RateLimitTier.SILVER)(hc)
    }

  }

  "deleteApplication" should {
    "delete an application in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.deleteApplication(app.wso2ApplicationName)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest.deleteApplication(app.wso2ApplicationName))

      verify(mockAwsApiGatewayConnector).deleteApplication(app.wso2ApplicationName)(hc)
    }
  }
}
