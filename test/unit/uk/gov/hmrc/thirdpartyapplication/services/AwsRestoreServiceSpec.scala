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

import org.mockito.{ArgumentCaptor, ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.AwsApiGatewayConnector
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.BRONZE
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AwsRestoreService

import scala.concurrent.Future

class AwsRestoreServiceSpec extends UnitSpec with ScalaFutures with MockitoSugar with ArgumentMatchersSugar {

  trait Setup {
    def buildApplication(applicationName: String, serverToken: String): ApplicationData = {
      ApplicationData.create(
        CreateApplicationRequest(
          name = applicationName,
          environment = Environment.PRODUCTION,
          collaborators = Set(Collaborator("foo@bar.com", Role.ADMINISTRATOR))
        ),
        "",
        "",
        applicationName,
        EnvironmentToken("", "", serverToken, List.empty))
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

      val captor: ArgumentCaptor[ApplicationData => Unit] = ArgumentCaptor.forClass(classOf[ApplicationData => Unit])

      when(mockApplicationRepository.processAll(captor.capture())).thenReturn(Future.successful(()))
      when(mockApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc))
        .thenReturn(Future.successful(HasSucceeded))

      await(awsRestoreService.restoreData())

      val capturedValue = captor.getValue

      capturedValue(application)
      verify(mockApiGatewayConnector).createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc)
    }
  }
}
