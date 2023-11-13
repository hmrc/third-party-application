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

import java.util.UUID
import scala.concurrent.Future

import org.mockito.ArgumentMatchersSugar

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ClientId, Environment}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RateLimitTier
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.CreateApplicationRequestV1
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.util._

class AwsRestoreServiceSpec extends AsyncHmrcSpec with ArgumentMatchersSugar with FixedClock with CollaboratorTestData {

  trait Setup extends ApplicationRepositoryMockModule with UpliftRequestSamples {

    def buildApplication(applicationName: String, serverToken: String): StoredApplication = {
      StoredApplication.create(
        CreateApplicationRequestV1.create(
          name = applicationName,
          access = Access.Standard(),
          environment = Environment.PRODUCTION,
          collaborators = Set("foo@bar.com".admin()),
          description = None,
          subscriptions = None
        ),
        applicationName,
        StoredToken(ClientId(""), serverToken, List.empty),
        createdOn = now
      )
    }

    val mockApiGatewayConnector: AwsApiGatewayConnector = mock[AwsApiGatewayConnector]

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val awsRestoreService: AwsRestoreService = new AwsRestoreService(mockApiGatewayConnector, ApplicationRepoMock.aMock)
  }

  "restoreData" should {
    "republish all Applications" in new Setup {
      val serverToken: String            = UUID.randomUUID().toString
      val application: StoredApplication = buildApplication("foo", serverToken)

      ApplicationRepoMock.ProcessAll.thenReturn()
      when(mockApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, serverToken, RateLimitTier.BRONZE)(hc))
        .thenReturn(Future.successful(HasSucceeded))

      await(awsRestoreService.restoreData())

      val functionCaptured = ApplicationRepoMock.ProcessAll.verify()
      functionCaptured(application)
      verify(mockApiGatewayConnector).createOrUpdateApplication(application.wso2ApplicationName, serverToken, RateLimitTier.BRONZE)(hc)
    }
  }
}
