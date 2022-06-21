/*
 * Copyright 2022 HM Revenue & Customs
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
import com.github.t3hnar.bcrypt._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import org.mockito.captor.ArgCaptor
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.{AuditServiceMockModule, ClientSecretServiceMockModule}
import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models._

import java.time.LocalDateTime

class CredentialServiceSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  trait Setup extends ApplicationRepositoryMockModule with AuditServiceMockModule with ClientSecretServiceMockModule with EmailConnectorMockModule {
    implicit val hc: HeaderCarrier                           = HeaderCarrier()
    val mockLogger: Logger                                   = mock[Logger]
    val clientSecretLimit                                    = 5
    val credentialConfig: CredentialConfig                   = CredentialConfig(clientSecretLimit)
    val mockApiPlatformEventService: ApiPlatformEventService = mock[ApiPlatformEventService]
    when(mockApiPlatformEventService.sendClientSecretAddedEvent(any[ApplicationData], any[String])(any[HeaderCarrier])).thenReturn(Future.successful(true))
    when(mockApiPlatformEventService.sendClientSecretRemovedEvent(any[ApplicationData], any[String])(any[HeaderCarrier])).thenReturn(Future.successful(true))

    val underTest: CredentialService =
      new CredentialService(
        ApplicationRepoMock.aMock,
        AuditServiceMock.aMock,
        ClientSecretServiceMock.aMock,
        credentialConfig,
        mockApiPlatformEventService,
        EmailConnectorMock.aMock
      ) {
        override val logger = mockLogger
      }
  }

  private def aSecret(secret: String): ClientSecret = ClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4))

  private val loggedInUser     = "loggedin@example.com"
  private val anotherAdminUser = "admin@example.com"
  private val firstSecret      = aSecret("secret1")
  private val secondSecret     = aSecret("secret2")
  private val environmentToken = Token(ClientId("aaa"), "bbb", List(firstSecret, secondSecret), None)
  private val tokenResponse    = ApplicationTokenResponse(ClientId("aaa"), "bbb", List(ClientSecretResponse(firstSecret), ClientSecretResponse(secondSecret)))

  "fetch credentials" should {

    "return none when no application exists in the repository for the given application id" in new Setup {

      val applicationId = ApplicationId.random
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe None
    }

    "return tokens when application exists in the repository for the given application id" in new Setup {

      val applicationId   = ApplicationId.random
      val applicationData = anApplicationData(applicationId)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe Some(tokenResponse)
    }

  }

  "validate credentials" should {

    "return none when no application exists in the repository for the given client id" in new Setup {
      val clientId = ClientId("some-client-id")
      ApplicationRepoMock.FetchByClientId.thenReturnNoneWhen(clientId)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "aSecret")).value)

      result shouldBe None
    }

    "return none when credentials don't match with an application" in new Setup {
      val applicationData = anApplicationData(ApplicationId.random)
      val clientId        = applicationData.tokens.production.clientId

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ClientSecretServiceMock.ClientSecretIsValid.noMatchingClientSecret(applicationData.id, "wrongSecret", applicationData.tokens.production.clientSecrets)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "wrongSecret")).value)

      ApplicationRepoMock.RecordClientSecretUsage.verifyNeverCalled()
      result shouldBe None
    }

    "return application details when credentials match" in new Setup {
      val applicationData             = anApplicationData(ApplicationId.random)
      val updatedApplicationData      = applicationData.copy(lastAccess = Some(LocalDateTime.now))
      val expectedApplicationResponse = ApplicationResponse(data = updatedApplicationData)
      val clientId                    = applicationData.tokens.production.clientId
      val secret                      = UUID.randomUUID().toString
      val matchingClientSecret        = applicationData.tokens.production.clientSecrets.head

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ClientSecretServiceMock.ClientSecretIsValid
        .thenReturnValidationResult(applicationData.id, secret, applicationData.tokens.production.clientSecrets)(matchingClientSecret)
      ApplicationRepoMock.RecordClientSecretUsage.thenReturnWhen(applicationData.id, matchingClientSecret.id)(updatedApplicationData)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, secret)).value)

      result shouldBe Some(expectedApplicationResponse)
    }

    "return application details and write log if updating usage date fails" in new Setup {
      val applicationData             = anApplicationData(ApplicationId.random)
      val expectedApplicationResponse = ApplicationResponse(data = applicationData)
      val clientId                    = applicationData.tokens.production.clientId
      val secret                      = UUID.randomUUID().toString
      val matchingClientSecret        = applicationData.tokens.production.clientSecrets.head
      val thrownException             = new RuntimeException

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
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

  "addClientSecret" should {
    val applicationId   = ApplicationId.random
    val applicationData = anApplicationData(
      applicationId,
      collaborators = Set(Collaborator(loggedInUser, ADMINISTRATOR, UserId.random), Collaborator(anotherAdminUser, ADMINISTRATOR, UserId.random))
    )
    val secretRequest   = ClientSecretRequest(loggedInUser)

    "add the client secret and return it unmasked" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      EmailConnectorMock.SendAddedClientSecretNotification.thenReturnOk()

      val newSecretValue: String = "secret3"
      val secretName: String     = newSecretValue.takeRight(4)
      val hashedSecret           = newSecretValue.bcrypt
      val newClientSecret        = ClientSecret(secretName, hashedSecret = hashedSecret)

      ClientSecretServiceMock.GenerateClientSecret.thenReturnWithSpecificSecret(newClientSecret.id, newSecretValue)

      val updatedClientSecrets: List[ClientSecret] = applicationData.tokens.production.clientSecrets :+ newClientSecret
      val updatedEnvironmentToken: Token           = applicationData.tokens.production.copy(clientSecrets = updatedClientSecrets)
      val updatedApplicationTokens                 = applicationData.tokens.copy(production = updatedEnvironmentToken)
      val applicationWithNewClientSecret           = applicationData.copy(tokens = updatedApplicationTokens)

      ApplicationRepoMock.AddClientSecret.thenReturn(applicationId)(applicationWithNewClientSecret)

      val result: ApplicationTokenResponse = await(underTest.addClientSecret(applicationId, secretRequest))

      result.clientId shouldBe environmentToken.clientId
      result.accessToken shouldBe environmentToken.accessToken
      result.clientSecrets.dropRight(1) shouldBe tokenResponse.clientSecrets
      result.clientSecrets.last.secret shouldBe Some(newSecretValue)
      result.clientSecrets.last.name shouldBe secretName

      AuditServiceMock.Audit.verifyCalledWith(
        ClientSecretAdded,
        Map("applicationId" -> applicationId.value.toString, "newClientSecret" -> secretName, "clientSecretType" -> PRODUCTION.toString),
        hc
      )

      verify(mockApiPlatformEventService).sendClientSecretAddedEvent(*[ApplicationData], eqTo(result.clientSecrets.last.id))(*[HeaderCarrier])
    }

    "send a notification to all admins" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      EmailConnectorMock.SendAddedClientSecretNotification.thenReturnOk()
      AuditServiceMock.Audit.thenReturnSuccess()

      val newSecretValue: String = "secret3"
      val secretName: String     = newSecretValue.takeRight(4)
      val newClientSecret        = ClientSecret(secretName, hashedSecret = newSecretValue.bcrypt)
      ClientSecretServiceMock.GenerateClientSecret.thenReturnWithSpecificSecret(newClientSecret.id, newSecretValue)

      val updatedClientSecrets: List[ClientSecret] = applicationData.tokens.production.clientSecrets :+ newClientSecret
      val updatedEnvironmentToken: Token           = applicationData.tokens.production.copy(clientSecrets = updatedClientSecrets)
      val updatedApplicationTokens                 = applicationData.tokens.copy(production = updatedEnvironmentToken)
      val applicationWithNewClientSecret           = applicationData.copy(tokens = updatedApplicationTokens)
      ApplicationRepoMock.AddClientSecret.thenReturn(applicationId)(applicationWithNewClientSecret)

      await(underTest.addClientSecret(applicationId, secretRequest))

      EmailConnectorMock.SendAddedClientSecretNotification
        .verifyCalledWith(secretRequest.actorEmailAddress, secretName, applicationData.name, Set(loggedInUser, anotherAdminUser))
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      intercept[NotFoundException](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw a ClientSecretsLimitExceeded when app already contains 5 secrets" in new Setup {
      val prodTokenWith5Secrets       = environmentToken.copy(clientSecrets = List("1", "2", "3", "4", "5").map(v => ClientSecret(v, hashedSecret = "hashed-secret")))
      val applicationDataWith5Secrets = anApplicationData(applicationId).copy(tokens = ApplicationTokens(prodTokenWith5Secrets))

      ApplicationRepoMock.Fetch.thenReturn(applicationDataWith5Secrets)

      intercept[ClientSecretsLimitExceeded](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }
  }

  "deleteClientSecret" should {
    val applicationId   = ApplicationId.random
    val applicationData = anApplicationData(
      applicationId,
      collaborators = Set(Collaborator(loggedInUser, ADMINISTRATOR, UserId.random), Collaborator(anotherAdminUser, ADMINISTRATOR, UserId.random))
    )

    "remove a client secret form an app with more than one client secret" in new Setup {
      val clientSecretIdToRemove: String = firstSecret.id

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.DeleteClientSecret.succeeds(applicationData, clientSecretIdToRemove)
      EmailConnectorMock.SendRemovedClientSecretNotification.thenReturnOk()

      AuditServiceMock.Audit.thenReturnSuccessWhen(
        ClientSecretRemoved,
        Map("applicationId" -> applicationId.value.toString, "removedClientSecret" -> clientSecretIdToRemove)
      )

      val result = await(underTest.deleteClientSecret(applicationId, clientSecretIdToRemove, loggedInUser))

      val updatedClientSecrets = result.clientSecrets
      updatedClientSecrets should have size environmentToken.clientSecrets.size - 1
      updatedClientSecrets should not contain (firstSecret)

      AuditServiceMock.Audit.verifyCalledWith(
        ClientSecretRemoved,
        Map("applicationId" -> applicationId.value.toString, "removedClientSecret" -> clientSecretIdToRemove),
        hc
      )

      verify(mockApiPlatformEventService).sendClientSecretRemovedEvent(any[ApplicationData], eqTo(clientSecretIdToRemove))(any[HeaderCarrier])
    }

    "send a notification to all admins" in new Setup {
      val clientSecretIdToRemove: String = firstSecret.id

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.DeleteClientSecret.succeeds(applicationData, clientSecretIdToRemove)
      EmailConnectorMock.SendRemovedClientSecretNotification.thenReturnOk()
      AuditServiceMock.Audit.thenReturnSuccess()

      await(underTest.deleteClientSecret(applicationId, clientSecretIdToRemove, loggedInUser))

      EmailConnectorMock.SendRemovedClientSecretNotification
        .verifyCalledWith(loggedInUser, firstSecret.name, applicationData.name, Set(loggedInUser, anotherAdminUser))

      verify(mockApiPlatformEventService).sendClientSecretRemovedEvent(any[ApplicationData], eqTo(clientSecretIdToRemove))(any[HeaderCarrier])
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      val clientSecretIdToRemove: String = firstSecret.id

      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.deleteClientSecret(applicationId, clientSecretIdToRemove, loggedInUser)))

      ApplicationRepoMock.DeleteClientSecret.verifyNeverCalled()
      AuditServiceMock.Audit.verifyNeverCalled()
      EmailConnectorMock.SendRemovedClientSecretNotification.verifyNeverCalled()
    }

    "throw a NotFoundException when trying to delete a secret which does not exist" in new Setup {
      val clientSecretIdToRemove = "notARealSecret"

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[NotFoundException](await(underTest.deleteClientSecret(applicationId, clientSecretIdToRemove, loggedInUser)))

      ApplicationRepoMock.DeleteClientSecret.verifyNeverCalled()
      AuditServiceMock.Audit.verifyNeverCalled()
      EmailConnectorMock.SendRemovedClientSecretNotification.verifyNeverCalled()
    }
  }

  private val requestedByEmail = "john.smith@example.com"

  private def anApplicationData(
      applicationId: ApplicationId,
      state: ApplicationState = productionState(requestedByEmail),
      collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR, UserId.random))
    ) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(environmentToken),
      state,
      Standard(),
      LocalDateTime.now(clock),
      Some(LocalDateTime.now(clock))
    )
  }
}
