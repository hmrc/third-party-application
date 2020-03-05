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

import org.mockito.ArgumentMatchersSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.AwsApiGatewayConnector
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.BRONZE
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.AwsRestoreService
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import unit.uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

import scala.concurrent.Future

class AwsRestoreServiceSpec extends AsyncHmrcSpec with ArgumentMatchersSugar {

  trait Setup extends ApplicationRepositoryMockModule {
    def buildApplication(applicationName: String, serverToken: String): ApplicationData = {
      ApplicationData.create(
        CreateApplicationRequest(
          name = applicationName,
          environment = Environment.PRODUCTION,
          collaborators = Set(Collaborator("foo@bar.com", Role.ADMINISTRATOR))
        ),
        applicationName,
        EnvironmentToken("", serverToken, List.empty))
    }

    val mockApiGatewayConnector: AwsApiGatewayConnector = mock[AwsApiGatewayConnector]

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val awsRestoreService: AwsRestoreService = new AwsRestoreService(mockApiGatewayConnector, ApplicationRepoMock.aMock)
  }

  "restoreData" should {
    "republish all Applications" in new Setup {
      val serverToken: String = UUID.randomUUID().toString
      val application: ApplicationData = buildApplication("foo", serverToken)

      ApplicationRepoMock.ProcessAll.thenReturn()
      when(mockApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc))
        .thenReturn(Future.successful(HasSucceeded))

      await(awsRestoreService.restoreData())

      val functionCaptured = ApplicationRepoMock.ProcessAll.verify()
      functionCaptured(application)
      verify(mockApiGatewayConnector).createOrUpdateApplication(application.wso2ApplicationName, serverToken, BRONZE)(hc)
    }
  }
}
