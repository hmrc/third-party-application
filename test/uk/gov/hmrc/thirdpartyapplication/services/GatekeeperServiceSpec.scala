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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful

import com.github.t3hnar.bcrypt._
import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientId, Collaborator}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.RejectUpliftRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApiGatewayStoreMockModule, AuditServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens, ApplicationWithStateHistory}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, CollaboratorTestData, FixedClock}

class GatekeeperServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with CollaboratorTestData
    with FixedClock {

  private val requestedByEmail = "john.smith@example.com"

  private val bobTheGKUser = Actors.GatekeeperUser("bob")

  private def aSecret(secret: String) = ClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4))

  private val productionToken = Token(ClientId("aaa"), "bbb", List(aSecret("secret1"), aSecret("secret2")))

  private def aHistory(appId: ApplicationId, state: State = PENDING_GATEKEEPER_APPROVAL): StateHistory = {
    StateHistory(appId, state, Actors.AppCollaborator("anEmail".toLaxEmail), Some(TESTING), changedAt = FixedClock.now)
  }

  private def aStateHistoryResponse(appId: ApplicationId, state: State = PENDING_GATEKEEPER_APPROVAL) = {
    StateHistoryResponse(appId, state, Actors.AppCollaborator("anEmail".toLaxEmail), None, FixedClock.now)
  }

  private def anApplicationData(
      applicationId: ApplicationId,
      state: ApplicationState = productionState(requestedByEmail),
      collaborators: Set[Collaborator] = Set(loggedInUser.admin())
    ) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      state,
      Standard(),
      FixedClock.now,
      Some(FixedClock.now)
    )
  }

  trait Setup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with ApiGatewayStoreMockModule
      with StateHistoryRepositoryMockModule {

    lazy val locked            = false
    val mockEmailConnector     = mock[EmailConnector](withSettings.lenient())
    val response               = mock[HttpResponse]
    val mockApplicationService = mock[ApplicationService]

    val applicationResponseCreator = new ApplicationResponseCreator()

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val underTest = new GatekeeperService(
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      AuditServiceMock.aMock,
      mockEmailConnector,
      mockApplicationService,
      clock
    )

    StateHistoryRepoMock.Insert.thenAnswer()
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*[LaxEmailAddress], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedNotification(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationRejectedNotification(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationDeletedNotification(*, *[ApplicationId], *[LaxEmailAddress], *)(*)).thenReturn(successful(HasSucceeded))
  }

  "fetch nonTestingApps with submitted date" should {

    "return apps" in new Setup {
      val app1     = anApplicationData(ApplicationId.random)
      val app2     = anApplicationData(ApplicationId.random)
      val history1 = aHistory(app1.id)
      val history2 = aHistory(app2.id)

      ApplicationRepoMock.FetchStandardNonTestingApps.thenReturn(app1, app2)
      StateHistoryRepoMock.FetchLatestByState.thenReturnWhen(State.PENDING_GATEKEEPER_APPROVAL)(history1, history2)

      val result = await(underTest.fetchNonTestingAppsWithSubmittedDate())

      result should contain theSameElementsAs List(ApplicationWithUpliftRequest.create(app1, history1), ApplicationWithUpliftRequest.create(app2, history2))
    }
  }

  "fetch application with history" should {
    val appId = ApplicationId.random

    "return app" in new Setup {
      val app1    = anApplicationData(appId)
      val history = List(aHistory(app1.id), aHistory(app1.id, State.PRODUCTION))

      ApplicationRepoMock.Fetch.thenReturn(app1)
      StateHistoryRepoMock.FetchByApplicationId.thenReturnWhen(appId)(history: _*)

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
      val app1              = anApplicationData(appId)
      val returnedHistories = List(aHistory(app1.id), aHistory(app1.id, State.PRODUCTION))
      val expectedHistories = List(aStateHistoryResponse(app1.id), aStateHistoryResponse(app1.id, State.PRODUCTION))

      ApplicationRepoMock.Fetch.thenReturn(app1)
      StateHistoryRepoMock.FetchByApplicationId.thenReturnWhen(appId)(returnedHistories: _*)

      val result = await(underTest.fetchAppStateHistoryById(appId))

      result shouldBe expectedHistories
    }
  }

  "approveUplift" should {
    val applicationId            = ApplicationId.random
    val upliftRequestedBy        = "email@example.com"
    val gatekeeperUserId: String = "big.boss.gatekeeper"

    "update the state of the application" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer()

      val application          = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))
      val expectedApplication  = application.copy(state = pendingRequesterVerificationState(upliftRequestedBy))
      val expectedStateHistory = StateHistory(
        applicationId = expectedApplication.id,
        state = PENDING_REQUESTER_VERIFICATION,
        actor = Actors.GatekeeperUser(gatekeeperUserId),
        previousState = Some(PENDING_GATEKEEPER_APPROVAL),
        changedAt = FixedClock.now
      )

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
        eqTo(application.name),
        *,
        eqTo(Set(application.state.requestedByEmailAddress.get.toLaxEmail))
      )(*)
    }

    "send notification email to all admins except requester" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer()

      val admin1    = "admin1@example.com".admin()
      val admin2    = "admin2@example.com".admin()
      val requester = upliftRequestedBy.admin()
      val developer = "somedev@example.com".developer()

      val application = anApplicationData(
        applicationId,
        pendingGatekeeperApprovalState(upliftRequestedBy),
        collaborators = Set(admin1, admin2, requester, developer)
      )

      ApplicationRepoMock.Fetch.thenReturn(application)

      await(underTest.approveUplift(applicationId, gatekeeperUserId))

      verify(mockEmailConnector).sendApplicationApprovedNotification(application.name, Set(admin1.emailAddress, admin2.emailAddress))
    }
  }

  "rejectUplift" should {
    val applicationId       = ApplicationId.random
    val upliftRequestedBy   = "email@example.com"
    val gatekeeperUserId    = "big.boss.gatekeeper"
    val rejectReason        = "Reason of rejection"
    val rejectUpliftRequest = RejectUpliftRequest(gatekeeperUserId, rejectReason)
    val application         = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

    "update the state of the application" in new Setup {
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer()

      val expectedApplication  = application.copy(state = testingState())
      val expectedStateHistory = StateHistory(
        applicationId = application.id,
        state = TESTING,
        actor = Actors.GatekeeperUser(gatekeeperUserId),
        previousState = Some(PENDING_GATEKEEPER_APPROVAL),
        notes = Some(rejectReason),
        changedAt = FixedClock.now
      )

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
        application.name,
        application.admins.map(_.emailAddress),
        rejectUpliftRequest.reason
      )
    }

  }

  "resendVerification" should {
    val applicationId            = ApplicationId.random
    val upliftRequestedBy        = "email@example.com"
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
        eqTo(application.name),
        *,
        eqTo(Set(application.state.requestedByEmailAddress.get.toLaxEmail))
      )(*)
    }
  }

  "blockApplication" should {

    val applicationId      = ApplicationId.random
    val applicationData    = anApplicationData(applicationId)
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

    val applicationId      = ApplicationId.random
    val applicationData    = anApplicationData(applicationId).copy(blocked = true)
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

  "fetchAppStateHistories" should {
    "return correct state history values" in new Setup {
      val appId1   = ApplicationId.random
      val appId2   = ApplicationId.random
      val ts1      = FixedClock.now
      val ts2      = FixedClock.now
      val ts3      = FixedClock.now
      val history1 = ApplicationWithStateHistory(
        appId1,
        "app1",
        2,
        List(
          StateHistory(appId1, State.TESTING, bobTheGKUser, None, None, ts1),
          StateHistory(appId1, State.PRODUCTION, bobTheGKUser, Some(State.TESTING), None, ts2)
        )
      )
      val history2 = ApplicationWithStateHistory(
        appId2,
        "app2",
        2,
        List(
          StateHistory(appId2, State.TESTING, bobTheGKUser, None, None, ts3)
        )
      )
      ApplicationRepoMock.FetchProdAppStateHistories.thenReturn(history1, history2)

      val result = await(underTest.fetchAppStateHistories())
      result shouldBe List(
        ApplicationStateHistory(
          appId1,
          "app1",
          2,
          List(
            ApplicationStateHistoryItem(State.TESTING, ts1),
            ApplicationStateHistoryItem(State.PRODUCTION, ts2)
          )
        ),
        ApplicationStateHistory(
          appId2,
          "app2",
          2,
          List(
            ApplicationStateHistoryItem(State.TESTING, ts3)
          )
        )
      )
    }
  }
}
