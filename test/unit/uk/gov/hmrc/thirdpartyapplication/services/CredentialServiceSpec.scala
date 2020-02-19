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

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.mockito.ArgumentCaptor
import play.api.LoggerLike
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import unit.uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import unit.uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

import scala.concurrent.Future.successful

class CredentialServiceSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  trait Setup extends ApplicationRepositoryMockModule with AuditServiceMockModule {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val mockLogger: LoggerLike = mock[LoggerLike]
    val mockGenerateSecret: () => String = mock[() => String]
    val clientSecretLimit = 5
    val credentialConfig: CredentialConfig = CredentialConfig(clientSecretLimit)

    val underTest: CredentialService = new CredentialService(ApplicationRepoMock.aMock, AuditServiceMock.aMock, credentialConfig) {
      override val logger: LoggerLike = mockLogger
      override val generateSecret: () => String = mockGenerateSecret
    }
  }

  private def aSecret(secret: String): ClientSecret = {
    ClientSecret("", secret)
  }

  private val loggedInUser = "loggedin@example.com"
  private val firstSecret = aSecret("secret1")
  private val secondSecret = aSecret("secret2")
  private val environmentToken = EnvironmentToken("aaa", "bbb", List(firstSecret, secondSecret))
  private val firstSecretResponse = firstSecret.copy(name = "••••••••••••••••••••••••••••••••ret1")
  private val secondSecretResponse = secondSecret.copy(name = "••••••••••••••••••••••••••••••••ret2")
  private val environmentTokenResponse = EnvironmentTokenResponse("aaa", "bbb", List(firstSecretResponse, secondSecretResponse))

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

      result shouldBe Some(environmentTokenResponse)
    }
  }

  "validate credentials" should {

    "return none when no application exists in the repository for the given client id" in new Setup {
      val clientId = "some-client-id"
      ApplicationRepoMock.FetchByClientId.thenReturnNoneWhen(clientId)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "aSecret")))

      result shouldBe None
    }

    "return none when credentials don't match with an application" in new Setup {
      val applicationData = anApplicationData(randomUUID())
      val clientId = applicationData.tokens.production.clientId
      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "wrongSecret")))

      ApplicationRepoMock.RecordClientSecretUsage.verifyNeverCalled()
      result shouldBe None
    }

    "return environment when credentials match with an application" in new Setup {
      val applicationData = anApplicationData(randomUUID())
      val clientId = applicationData.tokens.production.clientId
      val applicationId = applicationData.id.toString
      val clientSecret = applicationData.tokens.production.clientSecrets.head.secret

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ApplicationRepoMock.RecordClientSecretUsage.thenReturnWhen(applicationId, clientSecret)(applicationData)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, clientSecret)))

      result shouldBe Some(PRODUCTION)
    }

    "return environment but write log if updating usage date fails" in new Setup {
      val applicationData = anApplicationData(randomUUID())
      val clientId = applicationData.tokens.production.clientId
      val applicationId = applicationData.id.toString
      val clientSecret = applicationData.tokens.production.clientSecrets.head.secret
      val thrownException = new RuntimeException

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ApplicationRepoMock.RecordClientSecretUsage.thenFail(thrownException)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, clientSecret)))

      val exceptionCaptor: ArgumentCaptor[Throwable] = ArgumentCaptor.forClass(classOf[Throwable])
      verify(mockLogger).warn(any[String], exceptionCaptor.capture())

      exceptionCaptor.getValue shouldBe thrownException
      result shouldBe Some(PRODUCTION)
    }
  }

  "addClientSecret" should {
    val applicationId = randomUUID()
    val applicationData = anApplicationData(applicationId)
    val secretRequest = ClientSecretRequest("secret-1")

    "add the client secret and return it unmasked in the name" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenAnswer((a: ApplicationData) => successful(a))
      val newSecretValue: String = "secret3"
      val maskedSecretValue: String = s"••••••••••••••••••••••••••••••••ret3"
      when(mockGenerateSecret.apply()).thenReturn(newSecretValue)

      val result = await(underTest.addClientSecret(applicationId, secretRequest))

      val savedApp = ApplicationRepoMock.Save.verifyCalled()
      val updatedProductionSecrets = savedApp.tokens.production.clientSecrets
      updatedProductionSecrets should have size environmentToken.clientSecrets.size + 1
      val newSecret = (updatedProductionSecrets diff environmentToken.clientSecrets).head
      result.clientId shouldBe environmentToken.clientId
      result.accessToken shouldBe environmentToken.accessToken
      result.clientSecrets.dropRight(1) shouldBe environmentTokenResponse.clientSecrets
      result.clientSecrets.last.secret shouldBe updatedProductionSecrets.last.secret
      result.clientSecrets.last.name shouldBe newSecretValue
      updatedProductionSecrets.last.name shouldBe maskedSecretValue

      AuditServiceMock.Audit.verifyCalled(
        ClientSecretAdded,
        Map("applicationId" -> applicationId.toString, "newClientSecret" -> newSecret.secret, "clientSecretType" -> PRODUCTION.toString),
        hc
      )
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      when(mockGenerateSecret.apply()).thenReturn(randomUUID.toString)
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      intercept[NotFoundException](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw a ClientSecretsLimitExceeded when app already contains 5 secrets" in new Setup {
      when(mockGenerateSecret.apply()).thenReturn(randomUUID.toString)
      val prodTokenWith5Secrets = environmentToken.copy(clientSecrets = List("1", "2", "3", "4", "5").map(v => ClientSecret(v)))
      val applicationDataWith5Secrets = anApplicationData(applicationId).copy(tokens = ApplicationTokens(prodTokenWith5Secrets))

      ApplicationRepoMock.Fetch.thenReturn(applicationDataWith5Secrets)

      intercept[ClientSecretsLimitExceeded](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }
  }

  "delete client secrets" should {

    val applicationId = randomUUID()
    val applicationData = anApplicationData(applicationId)

    "remove a client secret form an app with more than one client secret" in new Setup {

      val secretsToRemove = List("secret1")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenAnswer((a: ApplicationData) => successful(a))

      AuditServiceMock.Audit.thenReturnSuccessWhen(
        ClientSecretRemoved,
        Map("applicationId" -> applicationId.toString, "removedClientSecret" -> secretsToRemove.head)
      )

      val result = await(underTest.deleteClientSecrets(applicationId, secretsToRemove))

      val savedApp = ApplicationRepoMock.Save.verifyCalled()
      val updatedClientSecrets = savedApp.tokens.production.clientSecrets
      updatedClientSecrets should have size environmentToken.clientSecrets.size - secretsToRemove.length
      result shouldBe environmentTokenResponse.copy(clientSecrets = environmentTokenResponse.clientSecrets.drop(1))

      AuditServiceMock.Audit.verify(times(secretsToRemove.length))(
        ClientSecretRemoved,
        Map("applicationId" -> applicationId.toString, "removedClientSecret" -> secretsToRemove.head),
        hc
      )
    }

    "throw an IllegalArgumentException when requested to remove all secrets" in new Setup {

      val secretsToRemove = List("secret1", "secret2")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[IllegalArgumentException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      AuditServiceMock.Audit.verifyNeverCalled()
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      val secretsToRemove = List("secret1")

      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      AuditServiceMock.Audit.verifyNeverCalled()
    }

    "throw a NotFoundException when trying to delete a secret which does not exist" in new Setup {
      val secretsToRemove = List("notARealSecret")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

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
