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
import java.util.UUID.randomUUID

import com.github.t3hnar.bcrypt._
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.DateTime
import org.mockito.captor.ArgCaptor
import play.api.LoggerLike
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService.maskSecret
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import unit.uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import unit.uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import unit.uk.gov.hmrc.thirdpartyapplication.mocks.{AuditServiceMockModule, ClientSecretServiceMockModule}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful

class CredentialServiceSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  trait Setup extends ApplicationRepositoryMockModule with AuditServiceMockModule with ClientSecretServiceMockModule with EmailConnectorMockModule {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val mockLogger: LoggerLike = mock[LoggerLike]
    val clientSecretLimit = 5
    val credentialConfig: CredentialConfig = CredentialConfig(clientSecretLimit)

    val underTest: CredentialService =
      new CredentialService(ApplicationRepoMock.aMock, AuditServiceMock.aMock, ClientSecretServiceMock.aMock, credentialConfig, EmailConnectorMock.aMock) {
      override val logger: LoggerLike = mockLogger
    }
  }

  private def aSecret(secret: String): ClientSecret = ClientSecret(maskSecret(secret), secret, hashedSecret = secret.bcrypt(4))

  private val loggedInUser = "loggedin@example.com"
  private val anotherAdminUser = "admin@example.com"
  private val firstSecret = aSecret("secret1")
  private val secondSecret = aSecret("secret2")
  private val environmentToken = EnvironmentToken("aaa", "bbb", List(firstSecret, secondSecret))
  private val firstSecretResponse = firstSecret.copy(name = "••••••••••••••••••••••••••••••••ret1")
  private val secondSecretResponse = secondSecret.copy(name = "••••••••••••••••••••••••••••••••ret2")
  private val tokenResponse =
    ApplicationTokenResponse("aaa", "bbb", List(ClientSecretResponse(firstSecretResponse), ClientSecretResponse(secondSecretResponse)))

  "fetch credentials" should {

    "return none when no application exists in the repository for the given application id" in new Setup {

      val applicationId = randomUUID()
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe None
    }

    "return tokens when application exists in the repository for the given application id" in new Setup {

      val applicationId = randomUUID()
      val applicationData = anApplicationData(applicationId)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe Some(tokenResponse)
    }
  }

  "validate credentials" should {

    "return none when no application exists in the repository for the given client id" in new Setup {
      val clientId = "some-client-id"
      ApplicationRepoMock.FetchByClientId.thenReturnNoneWhen(clientId)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "aSecret")).value)

      result shouldBe None
    }

    "return none when credentials don't match with an application" in new Setup {
      val applicationData = anApplicationData(randomUUID())
      val clientId = applicationData.tokens.production.clientId

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ClientSecretServiceMock.ClientSecretIsValid.noMatchingClientSecret("wrongSecret", applicationData.tokens.production.clientSecrets)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "wrongSecret")).value)

      ApplicationRepoMock.RecordClientSecretUsage.verifyNeverCalled()
      result shouldBe None
    }

    "return application details when credentials match" in new Setup {
      val applicationData = anApplicationData(randomUUID())
      val updatedApplicationData = applicationData.copy(lastAccess = Some(DateTime.now))
      val expectedApplicationResponse = ApplicationResponse(data = updatedApplicationData)
      val clientId = applicationData.tokens.production.clientId
      val matchingClientSecret = applicationData.tokens.production.clientSecrets.head

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ClientSecretServiceMock.ClientSecretIsValid
        .thenReturnValidationResult(matchingClientSecret.secret, applicationData.tokens.production.clientSecrets)(matchingClientSecret)
      ApplicationRepoMock.RecordClientSecretUsage.thenReturnWhen(applicationData.id, matchingClientSecret.id)(updatedApplicationData)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, matchingClientSecret.secret)).value)

      result shouldBe Some(expectedApplicationResponse)
    }

    "return application details and write log if updating usage date fails" in new Setup {
      val applicationData = anApplicationData(randomUUID())
      val expectedApplicationResponse = ApplicationResponse(data = applicationData)
      val clientId = applicationData.tokens.production.clientId
      val matchingClientSecret = applicationData.tokens.production.clientSecrets.head
      val thrownException = new RuntimeException

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ClientSecretServiceMock.ClientSecretIsValid
        .thenReturnValidationResult(matchingClientSecret.secret, applicationData.tokens.production.clientSecrets)(matchingClientSecret)
      ApplicationRepoMock.RecordClientSecretUsage.thenFail(thrownException)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, matchingClientSecret.secret)).value)

      val exceptionCaptor = ArgCaptor[Throwable]
      verify(mockLogger).warn(any[String], exceptionCaptor)

      exceptionCaptor.value shouldBe thrownException
      result shouldBe Some(expectedApplicationResponse)
    }
  }

  "addClientSecret" should {
    val applicationId = randomUUID()
    val applicationData = anApplicationData(
      applicationId,
      collaborators = Set(Collaborator(loggedInUser, ADMINISTRATOR), Collaborator(anotherAdminUser, ADMINISTRATOR))
    )
    val secretRequest = ClientSecretRequest(loggedInUser)

    "add the client secret and return it unmasked" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      EmailConnectorMock.SendAddedClientSecretNotification.thenReturnOk()

      val newSecretValue: String = "secret3"
      val maskedSecretValue: String = s"••••••••••••••••••••••••••••••••ret3"
      val hashedSecret = newSecretValue.bcrypt
      val newClientSecret = ClientSecret(maskedSecretValue, newSecretValue, hashedSecret = hashedSecret)

      ClientSecretServiceMock.GenerateClientSecret.thenReturnWithSpecificSecret(newSecretValue)

      val updatedClientSecrets: List[ClientSecret] = applicationData.tokens.production.clientSecrets :+ newClientSecret
      val updatedEnvironmentToken: EnvironmentToken = applicationData.tokens.production.copy(clientSecrets = updatedClientSecrets)
      val updatedApplicationTokens = applicationData.tokens.copy(production = updatedEnvironmentToken)
      val applicationWithNewClientSecret = applicationData.copy(tokens = updatedApplicationTokens)

      ApplicationRepoMock.AddClientSecret.thenReturn(eqTo(applicationId), *)(applicationWithNewClientSecret)

      val result: ApplicationTokenResponse = await(underTest.addClientSecret(applicationId, secretRequest))

      result.clientId shouldBe environmentToken.clientId
      result.accessToken shouldBe environmentToken.accessToken
      result.clientSecrets.dropRight(1) shouldBe tokenResponse.clientSecrets
      result.clientSecrets.last.secret shouldBe Some(newSecretValue)
      result.clientSecrets.last.name shouldBe maskedSecretValue

      AuditServiceMock.Audit.verifyCalledWith(
        ClientSecretAdded,
        Map("applicationId" -> applicationId.toString, "newClientSecret" -> maskedSecretValue, "clientSecretType" -> PRODUCTION.toString),
        hc
      )
    }

    "send a notification to all admins" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      EmailConnectorMock.SendAddedClientSecretNotification.thenReturnOk()
      AuditServiceMock.Audit.thenReturnSuccess()

      val newSecretValue: String = "secret3"
      val maskedSecretValue: String = s"••••••••••••••••••••••••••••••••ret3"
      val newClientSecret = ClientSecret(maskedSecretValue, newSecretValue, hashedSecret = newSecretValue.bcrypt)
      ClientSecretServiceMock.GenerateClientSecret.thenReturnWithSpecificSecret(newSecretValue)

      val updatedClientSecrets: List[ClientSecret] = applicationData.tokens.production.clientSecrets :+ newClientSecret
      val updatedEnvironmentToken: EnvironmentToken = applicationData.tokens.production.copy(clientSecrets = updatedClientSecrets)
      val updatedApplicationTokens = applicationData.tokens.copy(production = updatedEnvironmentToken)
      val applicationWithNewClientSecret = applicationData.copy(tokens = updatedApplicationTokens)
      ApplicationRepoMock.AddClientSecret.thenReturn(eqTo(applicationId), *)(applicationWithNewClientSecret)

      await(underTest.addClientSecret(applicationId, secretRequest))

      EmailConnectorMock.SendAddedClientSecretNotification
        .verifyCalledWith(secretRequest.actorEmailAddress, maskedSecretValue, applicationData.name, Set(loggedInUser, anotherAdminUser))
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      intercept[NotFoundException](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw a ClientSecretsLimitExceeded when app already contains 5 secrets" in new Setup {
      val prodTokenWith5Secrets = environmentToken.copy(clientSecrets = List("1", "2", "3", "4", "5").map(v => ClientSecret(v, hashedSecret = "hashed-secret")))
      val applicationDataWith5Secrets = anApplicationData(applicationId).copy(tokens = ApplicationTokens(prodTokenWith5Secrets))

      ApplicationRepoMock.Fetch.thenReturn(applicationDataWith5Secrets)

      intercept[ClientSecretsLimitExceeded](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }
  }

  "delete client secrets" should {
    val applicationId = randomUUID()
    val applicationData = anApplicationData(
      applicationId,
      collaborators = Set(Collaborator(loggedInUser, ADMINISTRATOR), Collaborator(anotherAdminUser, ADMINISTRATOR))
    )

    "remove a client secret form an app with more than one client secret" in new Setup {
      val secretsToRemove = List(firstSecret.secret)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenAnswer((a: ApplicationData) => successful(a))
      EmailConnectorMock.SendRemovedClientSecretNotification.thenReturnOk()

      AuditServiceMock.Audit.thenReturnSuccessWhen(
        ClientSecretRemoved,
        Map("applicationId" -> applicationId.toString, "removedClientSecret" -> secretsToRemove.head)
      )

      val result = await(underTest.deleteClientSecrets(applicationId, loggedInUser, secretsToRemove))

      val savedApp = ApplicationRepoMock.Save.verifyCalled()
      val updatedClientSecrets = savedApp.tokens.production.clientSecrets
      updatedClientSecrets should have size environmentToken.clientSecrets.size - secretsToRemove.length
      result shouldBe tokenResponse.copy(clientSecrets = tokenResponse.clientSecrets.drop(1))

      AuditServiceMock.Audit.verify(times(secretsToRemove.length))(
        ClientSecretRemoved,
        Map("applicationId" -> applicationId.toString, "removedClientSecret" -> secretsToRemove.head),
        hc
      )
    }

    "send a notification to all admins" in new Setup {
      val secretsToRemove = List(firstSecret.secret)
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenAnswer((a: ApplicationData) => successful(a))
      EmailConnectorMock.SendRemovedClientSecretNotification.thenReturnOk()
      AuditServiceMock.Audit.thenReturnSuccess()

      await(underTest.deleteClientSecrets(applicationId, loggedInUser, secretsToRemove))

      EmailConnectorMock.SendRemovedClientSecretNotification
        .verifyCalledWith(loggedInUser, firstSecret.name, applicationData.name, Set(loggedInUser, anotherAdminUser))
    }

    "throw an IllegalArgumentException when requested to remove all secrets" in new Setup {

      val secretsToRemove = List("secret1", "secret2")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[IllegalArgumentException](await(underTest.deleteClientSecrets(applicationId, loggedInUser, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      AuditServiceMock.Audit.verifyNeverCalled()
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      val secretsToRemove = List("secret1")

      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, loggedInUser, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      AuditServiceMock.Audit.verifyNeverCalled()
    }

    "throw a NotFoundException when trying to delete a secret which does not exist" in new Setup {
      val secretsToRemove = List("notARealSecret")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, loggedInUser, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      AuditServiceMock.Audit.verifyNeverCalled()
    }
  }

  private val requestedByEmail = "john.smith@example.com"

  private def anApplicationData(applicationId: UUID, state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR))) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(environmentToken),
      state,
      Standard(List.empty, None, None),
      HmrcTime.now,
      Some(HmrcTime.now))
  }
}
