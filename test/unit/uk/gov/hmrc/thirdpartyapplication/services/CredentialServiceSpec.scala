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
import java.util.concurrent.TimeUnit

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.DateTimeUtils
import org.mockito.ArgumentCaptor
import org.scalatest.BeforeAndAfterAll
import play.api.LoggerLike
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.lock.LockKeeper
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import unit.uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

import scala.concurrent.Future.successful
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class CredentialServiceSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup extends ApplicationRepositoryMockModule {

    lazy val locked = false
    val mockStateHistoryRepository = mock[StateHistoryRepository](withSettings.lenient())
    val mockAuditService = mock[AuditService](withSettings.lenient())
    val mockEmailConnector = mock[EmailConnector](withSettings.lenient())
    val mockLogger = mock[LoggerLike]
    val response = mock[WSResponse]

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser,
      LOGGED_IN_USER_NAME_HEADER -> "John Smith"
    )

    val applicationResponseCreator = new ApplicationResponseCreator()

    val clientSecretLimit = 5
    val credentialConfig = CredentialConfig(clientSecretLimit)

    val underTest = new CredentialService(ApplicationRepoMock.aMock, mockAuditService, applicationResponseCreator, credentialConfig) {
      override val logger = mockLogger
    }
  }

  private def aSecret(secret: String): ClientSecret = {
    ClientSecret(secret, secret)
  }

  private val loggedInUser = "loggedin@example.com"
  private val environmentToken = EnvironmentToken("aaa", "bbb", "wso2Secret", List(aSecret("secret1"), aSecret("secret2")))

  trait LockedSetup extends Setup {
    override lazy val locked = true
  }

  class MockLockKeeper(locked: Boolean) extends LockKeeper {

    //noinspection ScalaStyle
    override def repo = null

    override def lockId = ""

    //noinspection ScalaStyle
    val forceLockReleaseAfter = null

    var callsMadeToLockKeeper: Int = 0

    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
      callsMadeToLockKeeper = callsMadeToLockKeeper + 1
      if (locked) {
        successful(None)
      } else {
        successful(Some(Await.result(body, Duration(1, TimeUnit.SECONDS))))
      }
    }
  }

  override def beforeAll() {
    DateTimeUtils.setCurrentMillisFixed(DateTimeUtils.currentTimeMillis())
  }

  override def afterAll() {
    DateTimeUtils.setCurrentMillisSystem()
  }

  "fetch credentials" should {

    "return none when no application exists in the repository for the given application id" in new Setup {

      val applicationId = UUID.randomUUID()
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe None
    }

    "return tokens when application exists in the repository for the given application id" in new Setup {

      val applicationId = UUID.randomUUID()
      val applicationData = anApplicationData(applicationId)
      val expectedResult = EnvironmentTokenResponse(environmentToken.clientId, environmentToken.accessToken, environmentToken.clientSecrets)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe Some(expectedResult)
    }
  }

  "fetch wso2 credentials by clientId" should {

    "return none when no application exists in the repository for the given application clientId" in new Setup {

      val clientId = "aClientId"
      ApplicationRepoMock.FetchByClientId.thenReturnNoneWhen(clientId)

      val result = await(underTest.fetchWso2Credentials(clientId))

      result shouldBe None
    }

    "return wso2 credentials for the given client id" in new Setup {

      val applicationId = UUID.randomUUID()
      val applicationData = anApplicationData(applicationId)

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(environmentToken.clientId)(applicationData)

      val result = await(underTest.fetchWso2Credentials(environmentToken.clientId))

      result shouldBe Some(Wso2Credentials(environmentToken.clientId, environmentToken.accessToken, environmentToken.wso2ClientSecret))
    }

    "fail when the repository fails to return the application" in new Setup {

      val applicationId = UUID.randomUUID()
      val applicationData = anApplicationData(applicationId)

      ApplicationRepoMock.FetchByClientId.thenFail(new RuntimeException("test error"))

      intercept[RuntimeException] {
        await(underTest.fetchWso2Credentials(applicationData.tokens.production.clientId))
      }
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
      val applicationData = anApplicationData(UUID.randomUUID())
      val clientId = applicationData.tokens.production.clientId
      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "wrongSecret")))

      ApplicationRepoMock.RecordClientSecretUsage.verifyNeverCalled()
      result shouldBe None
    }

    "return environment when credentials match with an application" in new Setup {
      val applicationData = anApplicationData(UUID.randomUUID())
      val clientId = applicationData.tokens.production.clientId
      val applicationId = applicationData.id.toString
      val clientSecret = applicationData.tokens.production.clientSecrets.head.secret

      ApplicationRepoMock.FetchByClientId.thenReturnWhen(clientId)(applicationData)
      ApplicationRepoMock.RecordClientSecretUsage.thenReturnWhen(applicationId, clientSecret)(applicationData)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, clientSecret)))

      result shouldBe Some(PRODUCTION)
    }

    "return environment but write log if updating usage date fails" in new Setup {
      val applicationData = anApplicationData(UUID.randomUUID())
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
    val applicationId = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId)
    val secretRequest = ClientSecretRequest("secret-1")

    "add the client secret" in new Setup {

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenAnswer((a: ApplicationData) => successful(a))

      val result = await(underTest.addClientSecret(applicationId, secretRequest))

      val savedApp = ApplicationRepoMock.Save.verifyCalled()
      val updatedProductionSecrets = savedApp.tokens.production.clientSecrets
      updatedProductionSecrets should have size environmentToken.clientSecrets.size + 1
      val newSecret = updatedProductionSecrets diff environmentToken.clientSecrets
      result shouldBe EnvironmentTokenResponse(environmentToken.clientId, environmentToken.accessToken, updatedProductionSecrets)
      verify(mockAuditService).audit(ClientSecretAdded,
        Map("applicationId" -> applicationId.toString, "newClientSecret" -> newSecret.head.secret, "clientSecretType" -> PRODUCTION.toString))
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      intercept[NotFoundException](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw a ClientSecretsLimitExceeded when app already contains 5 secrets" in new Setup {

      val prodTokenWith5Secrets = environmentToken.copy(clientSecrets = List("1", "2", "3", "4", "5").map(v => ClientSecret(v)))
      val applicationDataWith5Secrets = anApplicationData(applicationId).copy(tokens = ApplicationTokens(prodTokenWith5Secrets))

      ApplicationRepoMock.Fetch.thenReturn(applicationDataWith5Secrets)

      intercept[ClientSecretsLimitExceeded](await(underTest.addClientSecret(applicationId, secretRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }
  }

  "delete client secrets" should {

    val applicationId = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId)

    "remove a client secret form an app with more than one client secret" in new Setup {

      val secretsToRemove = List("secret1")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenAnswer((a: ApplicationData) => successful(a))

      when(mockAuditService.audit(ClientSecretRemoved, Map("applicationId" -> applicationId.toString,
        "removedClientSecret" -> secretsToRemove.head))).thenReturn(Future.successful(AuditResult.Success))

      val result = await(underTest.deleteClientSecrets(applicationId, secretsToRemove))

      val savedApp = ApplicationRepoMock.Save.verifyCalled()
      val updatedClientSecrets = savedApp.tokens.production.clientSecrets
      updatedClientSecrets should have size environmentToken.clientSecrets.size - secretsToRemove.length
      result shouldBe EnvironmentTokenResponse(environmentToken.clientId, environmentToken.accessToken, updatedClientSecrets)
      verify(mockAuditService, times(secretsToRemove.length)).audit(ClientSecretRemoved,
        Map("applicationId" -> applicationId.toString, "removedClientSecret" -> secretsToRemove.head))
    }

    "throw an IllegalArgumentException when requested to remove all secrets" in new Setup {

      val secretsToRemove = List("secret1", "secret2")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[IllegalArgumentException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      verify(mockAuditService, never).audit(*, *)(*)
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      val secretsToRemove = List("secret1")

      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      verify(mockAuditService, never).audit(*, *)(*)
    }

    "throw a NotFoundException when trying to delete a secret which does not exist" in new Setup {
      val secretsToRemove = List("notARealSecret")

      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      verify(mockAuditService, never).audit(*, *)(*)
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
      "aaaaaaaaaa",
      "aaaaaaaaaa",
      ApplicationTokens(environmentToken),
      state,
      Standard(List.empty, None, None),
      HmrcTime.now,
      Some(HmrcTime.now))
  }
}
