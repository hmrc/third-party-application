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
import org.mockito.Strictness
import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, ClientId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApiGatewayStoreMockModule, AuditServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{StoredToken, _}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, CollaboratorTestData}

class GatekeeperServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with CollaboratorTestData
    with FixedClock {

  private val requestedByEmail = appStateRequestByEmail

  private val bobTheGKUser = Actors.GatekeeperUser("bob")

  private def aSecret(secret: String) = StoredClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4))

  private val productionToken = StoredToken(ClientId("aaa"), "bbb", List(aSecret("secret1"), aSecret("secret2")))

  private def aHistory(appId: ApplicationId, state: State = State.PENDING_GATEKEEPER_APPROVAL): StateHistory = {
    StateHistory(appId, state, Actors.AppCollaborator("anEmail".toLaxEmail), Some(State.TESTING), changedAt = instant)
  }

  private def aStateHistoryResponse(appId: ApplicationId, state: State = State.PENDING_GATEKEEPER_APPROVAL) = {
    StateHistoryResponse(appId, state, Actors.AppCollaborator("anEmail".toLaxEmail), None, instant)
  }

  private def anApplicationData(
      applicationId: ApplicationId,
      state: ApplicationState = productionState(),
      collaborators: Set[Collaborator] = Set(adminTwo)
    ) = {
    StoredApplication(
      applicationId,
      ApplicationName("MyApp"),
      "myapp",
      collaborators,
      Some(CoreApplicationData.appDescription),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      state,
      Access.Standard(),
      instant,
      Some(instant)
    )
  }

  trait Setup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with ApiGatewayStoreMockModule
      with StateHistoryRepositoryMockModule {

    lazy val locked            = false
    val mockEmailConnector     = mock[EmailConnector](withSettings.strictness(Strictness.Lenient))
    val response               = mock[HttpResponse]
    val mockApplicationService = mock[ApplicationService]

    // val applicationResponseCreator = new ApplicationResponseCreator()

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
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*[LaxEmailAddress], *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationDeletedNotification(*[ApplicationName], *[ApplicationId], *[LaxEmailAddress], *)(*)).thenReturn(successful(HasSucceeded))
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

      result shouldBe ApplicationWithHistoryResponse(StoredApplication.asApplication(app1), history.map(StateHistoryResponse.from))
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

  "fetchAllWithSubscriptions" should {

    "return no matching applications if application has a subscription" in new Setup {
      ApplicationRepoMock.GetAppsWithSubscriptions.thenReturnNone()

      val result: List[GatekeeperAppSubsResponse] = await(underTest.fetchAllWithSubscriptions())

      result.size shouldBe 0
    }

    "return applications when there are no matching subscriptions" in new Setup {
      private val appWithSubs: GatekeeperAppSubsResponse =
        GatekeeperAppSubsResponse(id = ApplicationId.random, name = ApplicationName("name"), lastAccess = None, apiIdentifiers = Set())
      ApplicationRepoMock.GetAppsWithSubscriptions.thenReturn(appWithSubs)

      val result: List[GatekeeperAppSubsResponse] = await(underTest.fetchAllWithSubscriptions())

      result.size shouldBe 1
      result shouldBe List(appWithSubs)
    }
  }

  "fetchAppStateHistories" should {
    "return correct state history values" in new Setup {
      val appId1   = ApplicationId.random
      val appId2   = ApplicationId.random
      val ts1      = instant
      val ts2      = instant
      val ts3      = instant
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
        ApplicationStateHistoryResponse(
          appId1,
          "app1",
          2,
          List(
            ApplicationStateHistoryResponse.Item(State.TESTING, ts1),
            ApplicationStateHistoryResponse.Item(State.PRODUCTION, ts2)
          )
        ),
        ApplicationStateHistoryResponse(
          appId2,
          "app2",
          2,
          List(
            ApplicationStateHistoryResponse.Item(State.TESTING, ts3)
          )
        )
      )
    }
  }
}
