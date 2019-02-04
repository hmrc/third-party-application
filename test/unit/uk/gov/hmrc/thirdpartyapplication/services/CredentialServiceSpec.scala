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
import java.util.concurrent.TimeUnit

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.DateTimeUtils
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.lock.LockKeeper
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.Future.successful
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class CredentialServiceSpec extends UnitSpec with ScalaFutures with MockitoSugar with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup {

    lazy val locked = false
    val mockWSO2APIStore = mock[Wso2ApiStore]
    val mockApplicationRepository = mock[ApplicationRepository]
    val mockStateHistoryRepository = mock[StateHistoryRepository]
    val mockAuditService = mock[AuditService]
    val mockEmailConnector = mock[EmailConnector]
    val response = mock[WSResponse]

    implicit val hc = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser,
      LOGGED_IN_USER_NAME_HEADER -> "John Smith"
    )

    val mockTrustedApplications = mock[TrustedApplications]
    when(mockTrustedApplications.isTrusted(any[ApplicationData])).thenReturn(false)
    val applicationResponseCreator = new ApplicationResponseCreator(mockTrustedApplications)

    val clientSecretLimit = 5
    val credentialConfig = CredentialConfig(clientSecretLimit)

    val underTest = new CredentialService(mockApplicationRepository, mockAuditService,mockTrustedApplications, applicationResponseCreator, credentialConfig)

    when(mockApplicationRepository.save(any())).thenAnswer(new Answer[Future[ApplicationData]] {
      override def answer(invocation: InvocationOnMock): Future[ApplicationData] = {
        successful(invocation.getArguments()(0).asInstanceOf[ApplicationData])
      }
    })
  }

  private def aSecret(secret: String): ClientSecret = {
    ClientSecret(secret, secret)
  }

  private val loggedInUser = "loggedin@example.com"
  private val productionToken = EnvironmentToken("aaa", "bbb", "wso2Secret", Seq(aSecret("secret1"), aSecret("secret2")))
  private val sandboxToken = EnvironmentToken("111", "222", "wso2SandboxSecret", Seq(aSecret("secret3"), aSecret("secret4")))

  trait LockedSetup extends Setup {
    override lazy val locked = true
  }

  class MockLockKeeper(locked: Boolean) extends LockKeeper {
    override def repo = null

    override def lockId = ""

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
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(None)

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe None
    }

    "return tokens when application exists in the repository for the given application id" in new Setup {

      val applicationId = UUID.randomUUID()
      val applicationData = anApplicationData(applicationId)
      val expectedResult = ApplicationTokensResponse(
        EnvironmentTokenResponse(productionToken.clientId, productionToken.accessToken, productionToken.clientSecrets),
        EnvironmentTokenResponse(sandboxToken.clientId, sandboxToken.accessToken, sandboxToken.clientSecrets))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(applicationData))

      val result = await(underTest.fetchCredentials(applicationId))

      result shouldBe Some(expectedResult)
    }
  }

  "fetch wso2 credentials by clientId" should {

    "return none when no application exists in the repository for the given application clientId" in new Setup {

      val clientId = "aClientId"
      when(mockApplicationRepository.fetchByClientId(clientId)).thenReturn(None)

      val result = await(underTest.fetchWso2Credentials(clientId))

      result shouldBe None
    }

    "return wso2 credentials for the given client id" in new Setup {

      val applicationId = UUID.randomUUID()
      val applicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchByClientId(productionToken.clientId)).thenReturn(Some(applicationData))

      val result = await(underTest.fetchWso2Credentials(productionToken.clientId))

      result shouldBe Some(Wso2Credentials(productionToken.clientId, productionToken.accessToken, productionToken.wso2ClientSecret))
    }

    "fail when the repository fails to return the application" in new Setup {

      val applicationId = UUID.randomUUID()
      val applicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetch(applicationId)).thenThrow(new RuntimeException("test error"))

      intercept[RuntimeException] {
        await(underTest.fetchWso2Credentials(applicationData.tokens.production.clientId))
      }
    }
  }

  "validate credentials" should {

    "return none when no application exists in the repository for the given client id" in new Setup {

      val clientId = "some-client-id"
      when(mockApplicationRepository.fetchByClientId(clientId)).thenReturn(None)

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "aSecret")))

      result shouldBe None
    }

    "return none when credentials don't match with an application" in new Setup {

      val applicationData = anApplicationData(UUID.randomUUID())
      val clientId = applicationData.tokens.production.clientId
      when(mockApplicationRepository.fetchByClientId(clientId)).thenReturn(Some(applicationData))

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, "wrongSecret")))

      result shouldBe None
    }

    "return none when sandbox secret is used with production client id" in new Setup {

      val applicationData = anApplicationData(UUID.randomUUID())
      val productionClientId = applicationData.tokens.production.clientId
      when(mockApplicationRepository.fetchByClientId(productionClientId)).thenReturn(Some(applicationData))

      val result = await(underTest.validateCredentials(ValidationRequest(productionClientId, applicationData.tokens.sandbox.clientSecrets.head.secret)))

      result shouldBe None
    }

    "return environment when credentials match with an application" in new Setup {

      val applicationData = anApplicationData(UUID.randomUUID())
      val clientId = applicationData.tokens.production.clientId
      when(mockApplicationRepository.fetchByClientId(clientId)).thenReturn(Some(applicationData))

      val result = await(underTest.validateCredentials(ValidationRequest(clientId, applicationData.tokens.production.clientSecrets.head.secret)))

      result shouldBe Some(PRODUCTION)
    }
  }

  "addClientSecret" should {
    val applicationId = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId)
    val secretRequest = ClientSecretRequest("secret-1")

    "add the client secret for production" in new Setup {
      val captor = ArgumentCaptor.forClass(classOf[ApplicationData])

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))

      val result = await(underTest.addClientSecret(applicationId, secretRequest))

      verify(mockApplicationRepository).save(captor.capture())
      val updatedProductionSecrets = captor.getValue.tokens.production.clientSecrets
      updatedProductionSecrets should have size productionToken.clientSecrets.size + 1
      val newSecret = updatedProductionSecrets diff productionToken.clientSecrets
      result shouldBe ApplicationTokensResponse.create(ApplicationTokens(productionToken.copy(clientSecrets = updatedProductionSecrets), sandboxToken))
      verify(mockAuditService).audit(ClientSecretAdded,
        Map("applicationId" -> applicationId.toString, "newClientSecret" -> newSecret.head.secret, "clientSecretType" -> PRODUCTION.toString))
    }

    "add the client secret for a Sandbox app" in new Setup {

      val sandboxAppData = applicationData.copy(environment = "SANDBOX")

      val captor = ArgumentCaptor.forClass(classOf[ApplicationData])

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(sandboxAppData)))

      val result = await(underTest.addClientSecret(applicationId, secretRequest))

      verify(mockApplicationRepository).save(captor.capture())
      val updatedSecrets = captor.getValue.tokens.production.clientSecrets
      updatedSecrets should have size productionToken.clientSecrets.size + 1
      result shouldBe ApplicationTokensResponse.create(ApplicationTokens(productionToken.copy(clientSecrets = updatedSecrets), sandboxToken))
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(None))

      intercept[NotFoundException](await(underTest.addClientSecret(applicationId, secretRequest)))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
    }

    "throw a ClientSecretsLimitExceeded when app already contains 5 secrets" in new Setup {

      val prodTokenWith5Secrets = productionToken.copy(clientSecrets = Seq(1, 2, 3, 4, 5).map(v => ClientSecret(v.toString)))
      val applicationDataWith5Secrets = anApplicationData(applicationId).copy(tokens = ApplicationTokens(prodTokenWith5Secrets, sandboxToken))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationDataWith5Secrets)))

      intercept[ClientSecretsLimitExceeded](await(underTest.addClientSecret(applicationId, secretRequest)))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
    }
  }

  "delete client secrets" should {

    val applicationId = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId)

    "remove a client secret form an app with more than one client secret" in new Setup {

      val secretsToRemove = Seq("secret1")
      val captor = ArgumentCaptor.forClass(classOf[ApplicationData])

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockAuditService.audit(ClientSecretRemoved, Map("applicationId" -> applicationId.toString,
        "removedClientSecret" -> secretsToRemove.head))).thenReturn(Future.successful(AuditResult.Success))

      val result = await(underTest.deleteClientSecrets(applicationId, secretsToRemove))

      verify(mockApplicationRepository).save(captor.capture())
      val updatedClientSecrets = captor.getValue.tokens.production.clientSecrets
      updatedClientSecrets should have size productionToken.clientSecrets.size - secretsToRemove.length
      result shouldBe ApplicationTokensResponse.create(ApplicationTokens(productionToken.copy(clientSecrets = updatedClientSecrets), sandboxToken))
      verify(mockAuditService, times(secretsToRemove.length)).audit(ClientSecretRemoved,
        Map("applicationId" -> applicationId.toString, "removedClientSecret" -> secretsToRemove.head))

    }

    "throw an IllegalArgumentException when reqested to remove all secrets" in new Setup {

      val secretsToRemove = Seq("secret1", "secret2")

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))

      intercept[IllegalArgumentException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
      verify(mockAuditService, never()).audit(any(), any())(any[HeaderCarrier])
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      val secretsToRemove = Seq("secret1")

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(None))

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
      verify(mockAuditService, never()).audit(any(), any())(any[HeaderCarrier])
    }

    "throw a NotFoundException when trying to delete a secret which does not exist" in new Setup {
      val secretsToRemove = Seq("notARealSecret")

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))

      intercept[NotFoundException](await(underTest.deleteClientSecrets(applicationId, secretsToRemove)))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
      verify(mockAuditService, never()).audit(any(), any())(any[HeaderCarrier])
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
      ApplicationTokens(productionToken, sandboxToken), state, Standard(Seq.empty, None, None))
  }
}
