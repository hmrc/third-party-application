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
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
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

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val awsRestoreService: AwsRestoreService = new AwsRestoreService(mockApiGatewayConnector, mockApplicationRepository)
  }

  "restoreData" should {
    "republish all Applications" in new Setup {
      val serverToken: String = UUID.randomUUID().toString
      val application: ApplicationData = buildApplication("foo", serverToken)

      when(mockApplicationRepository.fetchAll()).thenReturn(Future.successful(Seq(application)))
      when(mockApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc))
        .thenReturn(Future.successful(HasSucceeded))

      await(awsRestoreService.restoreData())

      verify(mockApiGatewayConnector).createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc)
    }
  }
}
