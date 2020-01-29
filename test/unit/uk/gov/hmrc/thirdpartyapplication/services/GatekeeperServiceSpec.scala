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

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.DateTimeUtils
import org.mockito.{ArgumentCaptor, captor}
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiSubscriptionFieldsConnector, EmailConnector, ThirdPartyDelegatedAuthorityConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.RejectUpliftRequest
import uk.gov.hmrc.thirdpartyapplication.models.ActorType.{COLLABORATOR, _}
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models.State._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.models.{State, _}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.Future
import scala.concurrent.Future.successful

class GatekeeperServiceSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  private val requestedByEmail = "john.smith@example.com"

  private def aSecret(secret: String) = ClientSecret(secret, secret)

  private val loggedInUser = "loggedin@example.com"
  private val productionToken = EnvironmentToken("aaa", "bbb", "wso2Secret", Seq(aSecret("secret1"), aSecret("secret2")))

  private def aHistory(appId: UUID, state: State = PENDING_GATEKEEPER_APPROVAL): StateHistory = {
    StateHistory(appId, state, Actor("anEmail", COLLABORATOR), Some(TESTING))
  }

  private def anApplicationData(applicationId: UUID, state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR))) = {
    ApplicationData(applicationId, "MyApp", "myapp",
      collaborators, Some("description"),
      "aaaaaaaaaa", "aaaaaaaaaa", "aaaaaaaaaa",
      ApplicationTokens(productionToken), state, Standard(Seq(), None, None), HmrcTime.now, Some(HmrcTime.now))
  }

  trait Setup {

    lazy val locked = false
    val mockApiGatewayStore = mock[ApiGatewayStore](withSettings.lenient())
    val mockApplicationRepository = mock[ApplicationRepository](withSettings.lenient())
    val mockStateHistoryRepository = mock[StateHistoryRepository](withSettings.lenient())
    val mockSubscriptionRepository = mock[SubscriptionRepository](withSettings.lenient())
    val mockAuditService = mock[AuditService](withSettings.lenient())
    val mockEmailConnector = mock[EmailConnector](withSettings.lenient())
    val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    val mockThirdPartyDelegatedAuthorityConnector = mock[ThirdPartyDelegatedAuthorityConnector]
    val response = mock[HttpResponse]
    val mockApplicationService = mock[ApplicationService]

    val applicationResponseCreator = new ApplicationResponseCreator()

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val underTest = new GatekeeperService(mockApplicationRepository,
      mockStateHistoryRepository,
      mockSubscriptionRepository,
      mockAuditService,
      mockEmailConnector,
      mockApiSubscriptionFieldsConnector,
      mockApiGatewayStore,
      applicationResponseCreator,
      mockThirdPartyDelegatedAuthorityConnector,
      mockApplicationService)

    when(mockApplicationRepository.save(*)).thenAnswer( (a: ApplicationData) => successful(a))
    when(mockStateHistoryRepository.insert(*)).thenAnswer( (s: StateHistory) => successful(s))
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*, *, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*, *, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedNotification(*, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationRejectedNotification(*, *, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationDeletedNotification(*, *, *)(*)).thenReturn(successful(response))
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

      result shouldBe ApplicationWithHistory(ApplicationResponse(data = app1), history.map(StateHistoryResponse.from))
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

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))

      result shouldBe UpliftApproved

      val appDataArgCaptor = captor.ArgCaptor[ApplicationData]
      verify(mockApplicationRepository).save(appDataArgCaptor.capture)
      verify(mockStateHistoryRepository).insert(expectedStateHistory)

      val savedApplication = appDataArgCaptor.value

      savedApplication.state.name shouldBe State.PENDING_REQUESTER_VERIFICATION
      savedApplication.state.verificationCode shouldBe defined
    }

    "rollback the application when storing the state history fails" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      when(mockStateHistoryRepository.insert(*)).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.approveUplift(applicationId, gatekeeperUserId))
      }

      verify(mockApplicationRepository).save(application)
    }

    "send an Audit event when an application uplift approved request is successful" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))
      verify(mockAuditService).audit(ApplicationUpliftApproved,
        AuditHelper.gatekeeperActionDetails(application), Map("gatekeeperId" -> gatekeeperUserId))
    }

    "fail with InvalidStateTransition when the application is not in PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      val application = anApplicationData(applicationId, pendingRequesterVerificationState("test@example.com"))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

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

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))
      verify(mockEmailConnector).sendApplicationApprovedAdminConfirmation(
        eqTo(application.name), *, eqTo(Set(application.state.requestedByEmailAddress.get)))(*)
    }

    "send notification email to all admins except requester" in new Setup {
      val admin1 = Collaborator("admin1@example.com", Role.ADMINISTRATOR)
      val admin2 = Collaborator("admin2@example.com", Role.ADMINISTRATOR)
      val requester = Collaborator(upliftRequestedBy, Role.ADMINISTRATOR)
      val developer = Collaborator("somedev@example.com", Role.DEVELOPER)

      val application = anApplicationData(
        applicationId, pendingGatekeeperApprovalState(upliftRequestedBy), collaborators = Set(admin1, admin2, requester, developer))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

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

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      val result = await(underTest.rejectUplift(applicationId, rejectUpliftRequest))

      result shouldBe UpliftRejected
      val appDataArgCaptor = ArgumentCaptor.forClass(classOf[ApplicationData])
      verify(mockApplicationRepository).save(expectedApplication)
      verify(mockStateHistoryRepository).insert(expectedStateHistory)
    }

    "rollback the application when storing the state history fails" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      when(mockStateHistoryRepository.insert(*)).thenReturn(Future.failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      }

      verify(mockApplicationRepository).save(application)
    }

    "send an Audit event when an application uplift is rejected" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      val result = await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      verify(mockAuditService).audit(ApplicationUpliftRejected,
        AuditHelper.gatekeeperActionDetails(application) + ("reason" -> rejectUpliftRequest.reason),
        Map("gatekeeperId" -> gatekeeperUserId))
    }

    "fail with InvalidStateTransition when the application is not in PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      when(mockApplicationRepository.fetch(applicationId))
        .thenReturn(successful(Some(application.copy(state = pendingRequesterVerificationState("test@example.com")))))

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
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

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

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      val result = await(underTest.resendVerification(applicationId, gatekeeperUserId))
      verify(mockAuditService).audit(ApplicationVerficationResent,
        AuditHelper.gatekeeperActionDetails(application), Map("gatekeeperId" -> gatekeeperUserId))
    }

    "fail with InvalidStateTransition when the application is not in PENDING_REQUESTER_VERIFICATION state" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState("test@example.com"))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      intercept[InvalidStateTransition] {
        await(underTest.resendVerification(applicationId, gatekeeperUserId))
      }
    }

    "send verification email to requester" in new Setup {
      val application = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(application)))

      val result = await(underTest.resendVerification(applicationId, gatekeeperUserId))
      verify(mockEmailConnector).sendApplicationApprovedAdminConfirmation(
        eqTo(application.name), *, eqTo(Set(application.state.requestedByEmailAddress.get)))(*)
    }
  }

  "blockApplication" should {

    val applicationId: UUID = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId)
    val updatedApplication = applicationData.copy(blocked = true)

    "set the block flag to true for an application" in new Setup {

      when(mockApplicationRepository.fetch(*)).thenReturn(successful(Option(applicationData)))
      when(mockApplicationRepository.save(*)).thenReturn(successful(updatedApplication))

      val result = await(underTest.blockApplication(applicationId))
      result shouldBe Blocked

      verify(mockApplicationRepository).fetch(applicationId)
      verify(mockApplicationRepository).save(updatedApplication)
    }
  }

  "unblockApplication" should {

    val applicationId: UUID = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId).copy(blocked = true)
    val updatedApplication = applicationData.copy(blocked = false)

    "set the block flag to false for an application" in new Setup {

      when(mockApplicationRepository.fetch(*)).thenReturn(successful(Option(applicationData)))
      when(mockApplicationRepository.save(*)).thenReturn(successful(updatedApplication))

      val result = await(underTest.unblockApplication(applicationId))
      result shouldBe Unblocked

      verify(mockApplicationRepository).fetch(applicationId)
      verify(mockApplicationRepository).save(updatedApplication)
    }
  }
}
