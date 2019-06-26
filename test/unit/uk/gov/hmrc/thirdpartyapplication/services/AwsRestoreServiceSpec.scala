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

import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.AwsApiGatewayConnector
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.BRONZE
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AwsRestoreService

import scala.concurrent.Future

class AwsRestoreServiceSpec extends UnitSpec with ScalaFutures with MockitoSugar {

  trait Setup {
    def buildApplication(applicationName: String, serverToken: String): ApplicationData = {
      ApplicationData.create(
        CreateApplicationRequest(
          name = applicationName,
          environment = Environment.PRODUCTION,
          collaborators = Set(Collaborator("foo@bar.com", Role.ADMINISTRATOR))),
        "",
        "",
        applicationName,
        ApplicationTokens(
          EnvironmentToken("", "", serverToken, Seq.empty),
          EnvironmentToken("", "", "", Seq.empty)))
    }

    val mockApiGatewayConnector: AwsApiGatewayConnector = mock[AwsApiGatewayConnector]
    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
    val mockSubscriptionRepository: SubscriptionRepository = mock[SubscriptionRepository]

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val awsRestoreService: AwsRestoreService =
      new AwsRestoreService(mockApiGatewayConnector, mockApplicationRepository, mockSubscriptionRepository)
  }

  "restoreData" should {
    "republish all Applications and their subscriptions" in new Setup {
      val applicationName = "foo"
      val serverToken: String = UUID.randomUUID().toString

      val application: ApplicationData = buildApplication(applicationName, serverToken)
      val subscription1 = new APIIdentifier("hello", "1.0")
      val subscription2 = new APIIdentifier("goodbye", "2.0")

      when(mockApplicationRepository.fetchAll()).thenReturn(Future.successful(Seq(application)))
      when(mockSubscriptionRepository.getSubscriptions(application.id)).thenReturn(Future.successful(Seq(subscription1, subscription2)))
      when(mockApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc))
        .thenReturn(Future.successful(HasSucceeded))

      await(awsRestoreService.restoreData())
    }

    "republish Applications with multi-segment API subscriptions" in new Setup {
      val applicationName = "foo"
      val serverToken: String = UUID.randomUUID().toString

      val application: ApplicationData = buildApplication(applicationName, serverToken)
      val multiSegmentSubscription = new APIIdentifier("hello/there", "1.0")
      val expectedAPIName = "hello--there--1.0"

      when(mockApplicationRepository.fetchAll()).thenReturn(Future.successful(Seq(application)))
      when(mockSubscriptionRepository.getSubscriptions(application.id)).thenReturn(Future.successful(Seq(multiSegmentSubscription)))
      when(mockApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc))
        .thenReturn(Future.successful(HasSucceeded))

      await(awsRestoreService.restoreData())
    }
  }
}
