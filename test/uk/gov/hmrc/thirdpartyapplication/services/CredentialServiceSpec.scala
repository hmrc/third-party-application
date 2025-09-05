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
import scala.concurrent.ExecutionContext.Implicits.global

import org.mockito.captor.ArgCaptor

import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.thirdpartyapplication.controllers.ValidationRequest
import uk.gov.hmrc.thirdpartyapplication.mocks.ClientSecretServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationQueries
import uk.gov.hmrc.thirdpartyapplication.util._

class CredentialServiceSpec extends AsyncHmrcSpec with StoredApplicationFixtures with CommonApplicationId {

  trait Setup extends ApplicationRepositoryMockModule
      with ClientSecretServiceMockModule {

    implicit val hc: HeaderCarrier                           = HeaderCarrier()
    val mockLogger: Logger                                   = mock[Logger]
    val clientSecretLimit                                    = 5
    val credentialConfig: CredentialConfig                   = CredentialConfig(clientSecretLimit)
    val mockApiPlatformEventService: ApiPlatformEventService = mock[ApiPlatformEventService]

    val underTest: CredentialService =
      new CredentialService(
        ApplicationRepoMock.aMock,
        ClientSecretServiceMock.aMock,
        credentialConfig
      ) {
        override val logger = mockLogger
      }

    val anotherAdminUser = "admin@example.com".toLaxEmail

    val applicationData  = storedApp
    val environmentToken = applicationData.tokens.production
    val firstSecret      = environmentToken.clientSecrets.head

    val prodTokenWith5Secrets       = environmentToken.copy(clientSecrets = List("1", "2", "3", "4", "5").map(v => StoredClientSecret(v, hashedSecret = "hashed-secret")))
    val applicationDataWith5Secrets = storedApp.copy(tokens = ApplicationTokens(prodTokenWith5Secrets))

    val expectedTokenResponse = ApplicationTokenResponse(environmentToken)
  }

  "fetch credentials" should {

    "return none when no application exists in the repository for the given application id" in new Setup {

      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      val result = await(underTest.fetch(applicationId))

      result shouldBe None
    }

    "return tokens when application exists in the repository for the given application id" in new Setup {

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      val result = await(underTest.fetch(applicationId))

      result shouldBe Some(expectedTokenResponse)
    }

  }

  "validate credentials" should {
    val clientId    = productionToken.clientId
    val expectedQry = ApplicationQueries.applicationByClientId(clientId)

    "return none when no application exists in the repository for the given client id" in new Setup {
      ApplicationRepoMock.FetchSingleApplicationByQuery.thenReturnNothingFor(expectedQry)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "aSecret")).value)

      result shouldBe None
    }

    "return none when credentials don't match with an application" in new Setup {
      ApplicationRepoMock.FetchSingleApplicationByQuery.thenReturnFor(expectedQry, applicationData)

      ClientSecretServiceMock.ClientSecretIsValid.noMatchingClientSecret(applicationData.id, "wrongSecret", applicationData.tokens.production.clientSecrets)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "wrongSecret")).value)

      ApplicationRepoMock.RecordClientSecretUsage.verifyNeverCalled()
      result shouldBe None
    }

    "return application details when credentials match" in new Setup {

      val updatedApplicationData      = applicationData.copy(lastAccess = Some(instant))
      val expectedApplicationResponse = updatedApplicationData.asAppWithCollaborators
      val secret                      = UUID.randomUUID().toString
      val matchingClientSecret        = applicationData.tokens.production.clientSecrets.head

      ApplicationRepoMock.FetchSingleApplicationByQuery.thenReturnFor(expectedQry, applicationData)
      ClientSecretServiceMock.ClientSecretIsValid
        .thenReturnValidationResult(applicationData.id, secret, environmentToken.clientSecrets)(matchingClientSecret)
      ApplicationRepoMock.RecordClientSecretUsage.thenReturnWhen(applicationData.id, matchingClientSecret.id)(updatedApplicationData)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, secret)).value)

      result shouldBe Some(expectedApplicationResponse)
    }

    "return application details and write log if updating usage date fails" in new Setup {

      val expectedApplicationResponse = applicationData.asAppWithCollaborators
      val secret                      = UUID.randomUUID().toString
      val matchingClientSecret        = applicationData.tokens.production.clientSecrets.head
      val thrownException             = new RuntimeException

      ApplicationRepoMock.FetchSingleApplicationByQuery.thenReturnFor(expectedQry, applicationData)
      ClientSecretServiceMock.ClientSecretIsValid
        .thenReturnValidationResult(applicationData.id, secret, applicationData.tokens.production.clientSecrets)(matchingClientSecret)
      ApplicationRepoMock.RecordClientSecretUsage.thenFail(thrownException)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, secret)).value)

      val exceptionCaptor = ArgCaptor[Throwable]
      verify(mockLogger).warn(any[String], exceptionCaptor)(*)

      exceptionCaptor hasCaptured thrownException
      result shouldBe Some(expectedApplicationResponse)
    }
  }
}
