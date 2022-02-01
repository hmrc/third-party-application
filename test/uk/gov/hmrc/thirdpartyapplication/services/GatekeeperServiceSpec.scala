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

import com.github.t3hnar.bcrypt._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import org.joda.time.DateTimeUtils
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.RejectUpliftRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType.{COLLABORATOR, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApiGatewayStoreMockModule, AuditServiceMockModule}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.SubscriptionRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule

class GatekeeperServiceSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  private val requestedByEmail = "john.smith@example.com"

  private def aSecret(secret: String) = ClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4))

  private val loggedInUser = "loggedin@example.com"
  private val productionToken = Token(ClientId("aaa"), "bbb", List(aSecret("secret1"), aSecret("secret2")))

  private def aHistory(appId: ApplicationId, state: State = PENDING_GATEKEEPER_APPROVAL): StateHistory = {
    StateHistory(appId, state, Actor("anEmail", COLLABORATOR), Some(TESTING))
  }

  private def aStateHistoryResponse(appId: ApplicationId, state: State = PENDING_GATEKEEPER_APPROVAL) = {
    StateHistoryResponse(appId, state, Actor("anEmail", COLLABORATOR), None, HmrcTime.now)
  }

  private def anApplicationData(applicationId: ApplicationId, state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR,  UserId.random))) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken), state, Standard(), HmrcTime.now, Some(HmrcTime.now))
  }

  trait Setup extends AuditServiceMockModule
    with ApplicationRepositoryMockModule
    with ApiGatewayStoreMockModule
    with SubscriptionRepositoryMockModule 
    with StateHistoryRepositoryMockModule {

    lazy val locked = false
    val mockEmailConnector = mock[EmailConnector](withSettings.lenient())
    val response = mock[HttpResponse]
    val mockApplicationService = mock[ApplicationService]

    val applicationResponseCreator = new ApplicationResponseCreator()

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val underTest = new GatekeeperService(
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      SubscriptionRepoMock.aMock,
      AuditServiceMock.aMock,
      mockEmailConnector,
      mockApplicationService)

    StateHistoryRepoMock.Insert.thenAnswer()
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedNotification(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationRejectedNotification(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationDeletedNotification(*, *, *)(*)).thenReturn(successful(HasSucceeded))
  }

  override def beforeAll() {
    DateTimeUtils.setCurrentMillisFixed(DateTimeUtils.currentTimeMillis())
  }

  override def afterAll() {
    DateTimeUtils.setCurrentMillisSystem()
  }

  "fetch nonTestingApps with submitted date" should {

    "return apps" in new Setup {
      val app1 = anApplicationData(ApplicationId.random)
      val app2 = anApplicationData(ApplicationId.random)
      val history1 = aHistory(app1.id)
      val history2 = aHistory(app2.id)

      ApplicationRepoMock.FetchStandardNonTestingApps.thenReturn(app1, app2)
      StateHistoryRepoMock.FetchLatestByState.thenReturnWhen(State.PENDING_GATEKEEPER_APPROVAL)(history1, history2)

      val result = await(underTest.fetchNonTestingAppsWithSubmittedDate())

      result should contain theSameElementsAs List(ApplicationWithUpliftRequest.create(app1, history1),
        ApplicationWithUpliftRequest.create(app2, history2))
    }
  }

  "fetch application with history" should {
    val appId = ApplicationId.random

    "return app" in new Setup {
      val app1 = anApplicationData(appId)
      val history = List(aHistory(app1.id), aHistory(app1.id, State.PRODUCTION))

      ApplicationRepoMock.Fetch.thenReturn(app1)
      StateHistoryRepoMock.FetchByApplicationId.thenReturnWhen(appId)(history : _*)

      val result = await(underTest.fetchAppWithHistory(appId))

      result shouldBe ApplicationWithHistory(ApplicationResponse(data = app1), history.map(StateHistoryResponse.from))
    }

    "throw not found exception" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.fetchAppWithHistory(appId)))
    }

    "propagate the exception when the app repository fail" in new Setup {
      ApplicationRepoMock.Fetch.thenFail(new RuntimeException("Expected test failure"))

      intercept[RuntimeException](await(underTest.fetchAppWithHistory(appId)))
    }

    "propagate the exception when the history repository fail" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(anApplicationData(appId))
      StateHistoryRepoMock.FetchByApplicationId.thenFailWith(new RuntimeException("Expected test failure"))

      intercept[RuntimeException](await(underTest.fetchAppWithHistory(appId)))
    }

  }

  "fetchAppStateHistoryById" should {
    val appId = ApplicationId.random

    "return app" in new Setup {
      val app1 = anApplicationData(appId)
      val returnedHistories = List(aHistory(app1.id), aHistory(app1.id, State.PRODUCTION))
      val expectedHistories = List(aStateHistoryResponse(app1.id), aStateHistoryResponse(app1.id, State.PRODUCTION))

      ApplicationRepoMock.Fetch.thenReturn(app1)
      StateHistoryRepoMock.FetchByApplicationId.thenReturnWhen(appId)(returnedHistories : _*)

      val result = await(underTest.fetchAppStateHistoryById(appId))

      result shouldBe expectedHistories
    }
  }

  "approveUplift" should {
    val applicationId = ApplicationId.random
    val upliftRequestedBy = "email@example.com"
    val gatekeeperUserId: String = "big.boss.gatekeeper"

    "update the state of the application" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer()

      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))
      val expectedApplication = application.copy(state = pendingRequesterVerificationState(upliftRequestedBy))
      val expectedStateHistory = StateHistory(applicationId = expectedApplication.id, state = PENDING_REQUESTER_VERIFICATION,
        actor = Actor(gatekeeperUserId, GATEKEEPER), previousState = Some(PENDING_GATEKEEPER_APPROVAL))

      ApplicationRepoMock.Fetch.thenReturn(application)

      val result = await(underTest.approveUplift(applicationId, gatekeeperUserId))

      result shouldBe UpliftApproved

      val savedApplication = ApplicationRepoMock.Save.verifyCalled()
      StateHistoryRepoMock.Insert.verifyCalledWith(expectedStateHistory)

      savedApplication.state.name shouldBe State.PENDING_REQUESTER_VERIFICATION
      savedApplication.state.verificationCode shouldBe defined
    }

    "rollback the application when storing the state history fails" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenReturn(mock[ApplicationData])
      StateHistoryRepoMock.Insert.thenFailsWith(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.approveUplift(applicationId, gatekeeperUserId))
      }

      // Saved after modification
      // And then the rollback
      ApplicationRepoMock.verify(times(2)).save(*)
    }

    "send an Audit event when an application uplift approved request is successful" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer()
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()
      
      await(underTest.approveUplift(applicationId, gatekeeperUserId))
      
      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserId
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationUpliftApproved
    }

    "fail with InvalidStateTransition when the application is not in PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      val application = anApplicationData(applicationId, pendingRequesterVerificationState("test@example.com"))

      ApplicationRepoMock.Fetch.thenReturn(application)

      intercept[InvalidStateTransition] {
        await(underTest.approveUplift(applicationId, gatekeeperUserId))
      }
    }

    "propagate the exception when the repository fail" in new Setup {
      ApplicationRepoMock.Fetch.thenFail(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.approveUplift(applicationId, gatekeeperUserId))
      }
    }

    "send confirmation email to admin uplift requester" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer()

      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      ApplicationRepoMock.Fetch.thenReturn(application)

      await(underTest.approveUplift(applicationId, gatekeeperUserId))
      
      verify(mockEmailConnector).sendApplicationApprovedAdminConfirmation(
        eqTo(application.name), *, eqTo(Set(application.state.requestedByEmailAddress.get)))(*)
    }

    "send notification email to all admins except requester" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer()

      val admin1 = Collaborator("admin1@example.com", Role.ADMINISTRATOR, UserId.random)
      val admin2 = Collaborator("admin2@example.com", Role.ADMINISTRATOR, UserId.random)
      val requester = Collaborator(upliftRequestedBy, Role.ADMINISTRATOR, UserId.random)
      val developer = Collaborator("somedev@example.com", Role.DEVELOPER, UserId.random)

      val application = anApplicationData(
        applicationId, pendingGatekeeperApprovalState(upliftRequestedBy), collaborators = Set(admin1, admin2, requester, developer))

      ApplicationRepoMock.Fetch.thenReturn(application)

      await(underTest.approveUplift(applicationId, gatekeeperUserId))
      
      verify(mockEmailConnector).sendApplicationApprovedNotification(application.name, Set(admin1.emailAddress, admin2.emailAddress))
    }
  }

  "rejectUplift" should {
    val applicationId = ApplicationId.random
    val upliftRequestedBy = "email@example.com"
    val gatekeeperUserId = "big.boss.gatekeeper"
    val rejectReason = "Reason of rejection"
    val rejectUpliftRequest = RejectUpliftRequest(gatekeeperUserId, rejectReason)
    val application = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

    "update the state of the application" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer()

      val expectedApplication = application.copy(state = testingState())
      val expectedStateHistory = StateHistory(applicationId = application.id, state = TESTING,
        actor = Actor(gatekeeperUserId, GATEKEEPER), previousState = Some(PENDING_GATEKEEPER_APPROVAL),
        notes = Some(rejectReason))

      ApplicationRepoMock.Fetch.thenReturn(application)

      val result = await(underTest.rejectUplift(applicationId, rejectUpliftRequest))

      result shouldBe UpliftRejected
      ApplicationRepoMock.Save.verifyCalled() shouldBe expectedApplication
      StateHistoryRepoMock.Insert.verifyCalledWith(expectedStateHistory)
    }

    "rollback the application when storing the state history fails" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer()
      StateHistoryRepoMock.Insert.thenFailsWith(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      }

      // Saved after modification
      // And then the rollback
      val captors = ApplicationRepoMock.Save.verifyCalled(times(2))
      captors.values.drop(1).head shouldBe application
    }

    "send an Audit event when an application uplift is rejected" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer()
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()

      await(underTest.rejectUplift(applicationId, rejectUpliftRequest))

      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserId
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationUpliftRejected
    }

    "fail with InvalidStateTransition when the application is not in PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(application.copy(state = pendingRequesterVerificationState("test@example.com")))

      intercept[InvalidStateTransition] {
        await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      }
    }

    "propagate the exception when the repository fail" in new Setup {
      ApplicationRepoMock.Fetch.thenFail(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      }
    }

    "send notification emails to all admins" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer()

      await(underTest.rejectUplift(applicationId, rejectUpliftRequest))
      
      verify(mockEmailConnector).sendApplicationRejectedNotification(
        application.name, application.admins.map(_.emailAddress), rejectUpliftRequest.reason)
    }

  }

  "resendVerification" should {
    val applicationId = ApplicationId.random
    val upliftRequestedBy = "email@example.com"
    val gatekeeperUserId: String = "big.boss.gatekeeper"

    "send an Audit event when a resend verification request is successful" in new Setup {
      val application = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      ApplicationRepoMock.Fetch.thenReturn(application)
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()

      await(underTest.resendVerification(applicationId, gatekeeperUserId))
      
      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserId
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationVerficationResent
    }

    "fail with InvalidStateTransition when the application is not in PENDING_REQUESTER_VERIFICATION state" in new Setup {
      val application = anApplicationData(applicationId, pendingGatekeeperApprovalState("test@example.com"))

      ApplicationRepoMock.Fetch.thenReturn(application)

      intercept[InvalidStateTransition] {
        await(underTest.resendVerification(applicationId, gatekeeperUserId))
      }
    }

    "send verification email to requester" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()

      val application = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      ApplicationRepoMock.Fetch.thenReturn(application)

      await(underTest.resendVerification(applicationId, gatekeeperUserId))
      
      verify(mockEmailConnector).sendApplicationApprovedAdminConfirmation(
        eqTo(application.name), *, eqTo(Set(application.state.requestedByEmailAddress.get)))(*)
    }
  }

  "blockApplication" should {

    val applicationId = ApplicationId.random
    val applicationData = anApplicationData(applicationId)
    val updatedApplication = applicationData.copy(blocked = true)

    "set the block flag to true for an application" in new Setup {

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedApplication)

      val result = await(underTest.blockApplication(applicationId))
      result shouldBe Blocked

      ApplicationRepoMock.Fetch.verifyCalledWith(applicationId)
      ApplicationRepoMock.Save.verifyCalledWith(updatedApplication)
    }
  }

  "unblockApplication" should {

    val applicationId = ApplicationId.random
    val applicationData = anApplicationData(applicationId).copy(blocked = true)
    val updatedApplication = applicationData.copy(blocked = false)

    "set the block flag to false for an application" in new Setup {

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedApplication)

      val result = await(underTest.unblockApplication(applicationId))
      result shouldBe Unblocked

      ApplicationRepoMock.Fetch.verifyCalledWith(applicationId)
      ApplicationRepoMock.Save.verifyCalledWith(updatedApplication)
    }
  }
}
