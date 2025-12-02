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

import org.mockito.Strictness
import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApiGatewayStoreMockModule, AuditServiceMockModule, QueryServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util._

class GatekeeperServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with CollaboratorTestData
    with ApplicationStateFixtures
    with StoredApplicationFixtures
    with FixedClock {

  private val bobTheGKUser = Actors.GatekeeperUser("bob")

  trait Setup extends AuditServiceMockModule
      with QueryServiceMockModule
      with ApplicationRepositoryMockModule
      with ApiGatewayStoreMockModule
      with StateHistoryRepositoryMockModule {

    lazy val locked            = false
    val mockEmailConnector     = mock[EmailConnector](withSettings.strictness(Strictness.Lenient))
    val response               = mock[HttpResponse]
    val mockApplicationService = mock[ApplicationService]

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val underTest = new GatekeeperService(
      QueryServiceMock.aMock,
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
