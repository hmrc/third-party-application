/*
 * Copyright 2018 HM Revenue & Customs
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

package services

import java.util.UUID

import common.uk.gov.hmrc.testutils.ApplicationStateUtil
import org.joda.time.DateTimeUtils
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{any, anyString, eq => eqTo}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.config.AppContext
import uk.gov.hmrc.connector.{ApiSubscriptionFieldsConnector, EmailConnector, ThirdPartyDelegatedAuthorityConnector}
import uk.gov.hmrc.controllers.{DeleteApplicationRequest, RejectUpliftRequest}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.models.ActorType.{COLLABORATOR, _}
import uk.gov.hmrc.models.Role._
import uk.gov.hmrc.models.State._
import uk.gov.hmrc.models.{State, _}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.services.AuditAction._
import uk.gov.hmrc.services._

import scala.concurrent.Future
import scala.concurrent.Future.successful

class GatekeeperServiceSpec extends UnitSpec with ScalaFutures with MockitoSugar with BeforeAndAfterAll with ApplicationStateUtil {

  private val requestedByEmail = "john.smith@example.com"

  private def aSecret(secret: String) = ClientSecret(secret, secret)

  private val loggedInUser = "loggedin@example.com"
  private val productionToken = EnvironmentToken("aaa", "bbb", "wso2Secret", Seq(aSecret("secret1"), aSecret("secret2")))
  private val sandboxToken = EnvironmentToken("111", "222", "wso2SandboxSecret", Seq(aSecret("secret3"), aSecret("secret4")))

  private def aHistory(appId: UUID, state: State = PENDING_GATEKEEPER_APPROVAL): StateHistory = {
    StateHistory(appId, state, Actor("anEmail", COLLABORATOR), Some(TESTING))
  }

  private def anApplicationData(applicationId: UUID, state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR))) = {
    ApplicationData(applicationId, "MyApp", "myapp",
      collaborators, Some("description"),
      "aaaaaaaaaa", "aaaaaaaaaa", "aaaaaaaaaa",
      ApplicationTokens(productionToken, sandboxToken), state, Standard(Seq(), None, None))
  }

  trait Setup {

    lazy val locked = false
    val mockWSO2APIStore = mock[WSO2APIStore]
    val mockApplicationRepository = mock[ApplicationRepository]
    val mockStateHistoryRepository = mock[StateHistoryRepository]
    val mockSubscriptionRepository = mock[SubscriptionRepository]
    val mockAuditService = mock[AuditService]
    val mockEmailConnector = mock[EmailConnector]
    val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    val mockThirdPartyDelegatedAuthorityConnector = mock[ThirdPartyDelegatedAuthorityConnector]
    val response = mock[HttpResponse]
    val mockAppContext = mock[AppContext]
    when(mockAppContext.trustedApplications).thenReturn(Seq.empty)

    val applicationResponseCreator = new ApplicationResponseCreator(mockAppContext)

    implicit val hc = HeaderCarrier()

    val underTest = new GatekeeperService(mockApplicationRepository,
      mockStateHistoryRepository,
      mockSubscriptionRepository,
      mockAuditService,
      mockEmailConnector,
      mockApiSubscriptionFieldsConnector,
      mockWSO2APIStore,
      applicationResponseCreator,
      mockAppContext,
      mockThirdPartyDelegatedAuthorityConnector)

    when(mockApplicationRepository.save(any())).thenAnswer(new Answer[Future[ApplicationData]] {
      override def answer(invocation: InvocationOnMock): Future[ApplicationData] = {
        successful(invocation.getArguments()(0).asInstanceOf[ApplicationData])
      }
    })
    when(mockStateHistoryRepository.insert(any())).thenAnswer(new Answer[Future[StateHistory]] {
      override def answer(invocation: InvocationOnMock): Future[StateHistory] = {
        successful(invocation.getArguments()(0).asInstanceOf[StateHistory])
      }
    })
    when(mockEmailConnector.sendRemovedCollaboratorNotification(anyString(), anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(anyString(), anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedNotification(anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationRejectedNotification(anyString(), any(), anyString())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationDeletedNotification(anyString(), anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
  }

  override def beforeAll() {
    DateTimeUtils.setCurrentMillisFixed(DateTimeUtils.currentTimeMillis())
  }

  override def afterAll() {
    DateTimeUtils.setCurrentMillisSystem()
  }

  "fetch nonTestingApps with submitted date" should {

    "return apps" in new Setup {
      val app1 = anApplicationData(UUID.randomUUID())
      val app2 = anApplicationData(UUID.randomUUID())
      val history1 = aHistory(app1.id)
      val history2 = aHistory(app2.id)

      when(mockApplicationRepository.fetchStandardNonTestingApps()).thenReturn(successful(Seq(app1, app2)))
      when(mockStateHistoryRepository.fetchByState(State.PENDING_GATEKEEPER_APPROVAL)).thenReturn(successful(Seq(history1, history2)))

      val result = await(underTest.fetchNonTestingAppsWithSubmittedDate())

      result should contain theSameElementsAs Seq(ApplicationWithUpliftRequest.create(app1, history1),
        ApplicationWithUpliftRequest.create(app2, history2))
    }
  }

  "fetch application with history" should {
    val appId: UUID = UUID.randomUUID()

    "return app" in new Setup {
      val app1 = anApplicationData(appId)
      val history = Seq(aHistory(app1.id), aHistory(app1.id, State.PRODUCTION))

      when(mockApplicationRepository.fetch(appId)).thenReturn(successful(Some(app1)))
      when(mockStateHistoryRepository.fetchByApplicationId(appId)).thenReturn(successful(history))

      val result = await(underTest.fetchAppWithHistory(appId))

      result shouldBe ApplicationWithHistory(ApplicationResponse(data = app1, clientId = None, trusted = false), history.map(StateHistoryResponse.from))
    }

    "throw not found exception" in new Setup {
      when(mockApplicationRepository.fetch(appId)).thenReturn(successful(None))

      intercept[NotFoundException](await(underTest.fetchAppWithHistory(appId)))
    }

    "propagate the exception when the app repository fail" in new Setup {
      when(mockApplicationRepository.fetch(appId)).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException](await(underTest.fetchAppWithHistory(appId)))
    }

    "propagate the exception when the history repository fail" in new Setup {
      when(mockApplicationRepository.fetch(appId)).thenReturn(successful(Some(anApplicationData(appId))))
      when(mockStateHistoryRepository.fetchByApplicationId(appId)).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException](await(underTest.fetchAppWithHistory(appId)))
    }

  }

  "approveUplift" should {
    val applicationId = UUID.randomUUID()
    val upliftRequestedBy = "email@example.com"
    val gatekeeperUserId: String = "big.boss.gatekeeper"

    "update the state of the application" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))
      val expectedApplication = application.copy(state = pendingRequesterVerificationState(upliftRequestedBy))
      val expectedStateHistory = StateHistory(applicationId = expectedApplication.id, state = PENDING_REQUESTER_VERIFICATION,
        actor = Actor(gatekeeperUserId, GATEKEEPER), previousState = Some(PENDING_GATEKEEPER_APPROVAL))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))

      result shouldBe UpliftApproved
      val appDataArgCaptor = ArgumentCaptor.forClass(classOf[ApplicationData])
      verify(mockApplicationRepository).save(appDataArgCaptor.capture())
      verify(mockStateHistoryRepository).insert(expectedStateHistory)

      val savedApplication = appDataArgCaptor.getValue

      savedApplication.state.name shouldBe State.PENDING_REQUESTER_VERIFICATION
      savedApplication.state.verificationCode shouldBe defined
    }

    "rollback the application when storing the state history fails" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      when(mockStateHistoryRepository.insert(any())).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.approveUplift(applicationId, gatekeeperUserId))
      }

      verify(mockApplicationRepository).save(application)
    }

    "send an Audit event when an application uplift approved request is successful" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))
      verify(mockAuditService).audit(ApplicationUpliftApproved,
        AuditHelper.gatekeeperActionDetails(application), Map("gatekeeperId" -> gatekeeperUserId))
    }

    "fail with InvalidStateTransition when the application is not in PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      val application = anApplicationData(applicationId, pendingRequesterVerificationState("test@example.com"))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      intercept[InvalidStateTransition] {
        await(underTest.approveUplift(applicationId, gatekeeperUserId))
      }
    }

    "propagate the exception when the repository fail" in new Setup {

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.approveUplift(applicationId, gatekeeperUserId))
      }
    }

    "send confirmation email to admin uplift requester" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))
      verify(mockEmailConnector).sendApplicationApprovedAdminConfirmation(
        eqTo(application.name), anyString(), eqTo(Set(application.state.requestedByEmailAddress.get)))(any[HeaderCarrier]())
    }

    "send notification email to all admins except requester" in new Setup {
      val admin1 = Collaborator("admin1@example.com", Role.ADMINISTRATOR)
      val admin2 = Collaborator("admin2@example.com", Role.ADMINISTRATOR)
      val requester = Collaborator(upliftRequestedBy, Role.ADMINISTRATOR)
      val developer = Collaborator("somedev@example.com", Role.DEVELOPER)

      val application = anApplicationData(
        applicationId, pendingGatekeeperApprovalState(upliftRequestedBy), collaborators = Set(admin1, admin2, requester, developer))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))
      verify(mockEmailConnector).sendApplicationApprovedNotification(application.name, Set(admin1.emailAddress, admin2.emailAddress))
    }
  }

  "rejectUplift" should {
    val applicationId = UUID.randomUUID()
    val upliftRequestedBy = "email@example.com"
    val gatekeeperUserId = "big.boss.gatekeeper"
    val rejectReason = "Reason of rejection"
    val rejectUpliftRequest = RejectUpliftRequest(gatekeeperUserId, rejectReason)
    val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

    "update the state of the application" in new Setup {
      val expectedApplication = application.copy(state = testingState())
      val expectedStateHistory = StateHistory(applicationId = application.id, state = TESTING,
        actor = Actor(gatekeeperUserId, GATEKEEPER), previousState = Some(PENDING_GATEKEEPER_APPROVAL),
        notes = Some(rejectReason))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.rejectUplift(applicationId, rejectUpliftRequest))

      result shouldBe UpliftRejected
      val appDataArgCaptor = ArgumentCaptor.forClass(classOf[ApplicationData])
      verify(mockApplicationRepository).save(expectedApplication)
      verify(mockStateHistoryRepository).insert(expectedStateHistory)
    }

    "rollback the application when storing the state history fails" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      when(mockStateHistoryRepository.insert(any())).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      }

      verify(mockApplicationRepository).save(application)
    }

    "send an Audit event when an application uplift is rejected" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      verify(mockAuditService).audit(ApplicationUpliftRejected,
        AuditHelper.gatekeeperActionDetails(application) + ("reason" -> rejectUpliftRequest.reason),
        Map("gatekeeperId" -> gatekeeperUserId))
    }

    "fail with InvalidStateTransition when the application is not in PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      when(mockApplicationRepository.fetch(applicationId))
        .thenReturn(Some(application.copy(state = pendingRequesterVerificationState("test@example.com"))))

      intercept[InvalidStateTransition] {
        await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      }
    }

    "propagate the exception when the repository fail" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      }
    }

    "send notification emails to all admins" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      verify(mockEmailConnector).sendApplicationRejectedNotification(
        application.name, application.admins.map(_.emailAddress), rejectUpliftRequest.reason)
    }

  }

  "resendVerification" should {
    val applicationId = UUID.randomUUID()
    val upliftRequestedBy = "email@example.com"
    val gatekeeperUserId: String = "big.boss.gatekeeper"

    "send an Audit event when a resend verification request is successful" in new Setup {
      val application = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.resendVerification(applicationId, gatekeeperUserId))
      verify(mockAuditService).audit(ApplicationVerficationResent,
        AuditHelper.gatekeeperActionDetails(application), Map("gatekeeperId" -> gatekeeperUserId))
    }

    "fail with InvalidStateTransition when the application is not in PENDING_REQUESTER_VERIFICATION state" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState("test@example.com"))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      intercept[InvalidStateTransition] {
        await(underTest.resendVerification(applicationId, gatekeeperUserId))
      }
    }

    "send verification email to requester" in new Setup {
      val application = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(Some(application))

      val result = await(underTest.resendVerification(applicationId, gatekeeperUserId))
      verify(mockEmailConnector).sendApplicationApprovedAdminConfirmation(
        eqTo(application.name), anyString(), eqTo(Set(application.state.requestedByEmailAddress.get)))(any[HeaderCarrier]())
    }
  }

  "deleting an application" should {
    val deleteRequestedBy = "email@example.com"
    val gatekeeperUserId = "big.boss.gatekeeper"
    val request = DeleteApplicationRequest(gatekeeperUserId, deleteRequestedBy)
    val applicationId = UUID.randomUUID()
    val application = anApplicationData(applicationId)
    val api1 = APIIdentifier("hello", "1.0")
    val api2 = APIIdentifier("goodbye", "1.0")

    trait DeleteApplicationSetup extends Setup {
      when(mockApplicationRepository.fetch(any())).thenReturn(Some(application))
      when(mockWSO2APIStore.getSubscriptions(any(), any(), any())(any[HeaderCarrier])).thenReturn(successful(Seq(api1, api2)))
      when(mockWSO2APIStore.removeSubscription(any(), any(), any(), any())(any[HeaderCarrier])).thenReturn(successful(HasSucceeded))
      when(mockSubscriptionRepository.remove(any(), any())).thenReturn(successful(HasSucceeded))
      when(mockWSO2APIStore.deleteApplication(any(), any(), any())(any[HeaderCarrier])).thenReturn(successful(HasSucceeded))
      when(mockApplicationRepository.delete(any())).thenReturn(successful(HasSucceeded))
      when(mockStateHistoryRepository.deleteByApplicationId(any())).thenReturn(successful(HasSucceeded))
      when(mockApiSubscriptionFieldsConnector.deleteSubscriptions(any())(any[HeaderCarrier])).thenReturn(successful(HasSucceeded))
      when(mockThirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(any())(any[HeaderCarrier])).thenReturn(successful(HasSucceeded))
    }

    "return a state change to indicate that the application has been deleted" in new DeleteApplicationSetup {
      val result = await(underTest.deleteApplication(applicationId, request))
      result shouldBe Deleted
    }

    "call to WSO2 to delete the application" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockWSO2APIStore).deleteApplication(eqTo(application.wso2Username), eqTo(application.wso2Password),
        eqTo(application.wso2ApplicationName))(any[HeaderCarrier])
    }

    "call to WSO2 to remove the subscriptions" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockWSO2APIStore).removeSubscription(eqTo(application.wso2Username), eqTo(application.wso2Password),
        eqTo(application.wso2ApplicationName), eqTo(api1))(any[HeaderCarrier])
      verify(mockWSO2APIStore).removeSubscription(eqTo(application.wso2Username), eqTo(application.wso2Password),
        eqTo(application.wso2ApplicationName), eqTo(api2))(any[HeaderCarrier])
    }

    "call to the API Subscription Fields service to delete subscription field data" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockApiSubscriptionFieldsConnector).deleteSubscriptions(eqTo(application.tokens.production.clientId))(any[HeaderCarrier])
    }

    "delete the application subscriptions from the repository" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockSubscriptionRepository).remove(eqTo(applicationId), eqTo(api1))
      verify(mockSubscriptionRepository).remove(eqTo(applicationId), eqTo(api2))
    }

    "delete the application from the repository" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockApplicationRepository).delete(applicationId)
    }

    "delete the application state history from the repository" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockStateHistoryRepository).deleteByApplicationId(applicationId)
    }

    "audit the application deletion" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockAuditService).audit(ApplicationDeleted,
        AuditHelper.gatekeeperActionDetails(application) + ("requestedByEmailAddress" -> deleteRequestedBy),
        Map("gatekeeperId" -> gatekeeperUserId))
    }

    "send the application deleted notification email" in new DeleteApplicationSetup {
      await(underTest.deleteApplication(applicationId, request))
      verify(mockEmailConnector).sendApplicationDeletedNotification(
        application.name, deleteRequestedBy, application.admins.map(_.emailAddress))
    }

    "silently ignore the delete request if no application exists for the application id (to ensure idempotency)" in new Setup {
      when(mockApplicationRepository.fetch(any())).thenReturn(None)

      val result = await(underTest.deleteApplication(applicationId, request))
      result shouldBe Deleted

      verify(mockApplicationRepository).fetch(applicationId)
      verifyNoMoreInteractions(mockWSO2APIStore, mockApplicationRepository, mockStateHistoryRepository,
        mockSubscriptionRepository, mockAuditService, mockEmailConnector, mockApiSubscriptionFieldsConnector)
    }
  }
}
