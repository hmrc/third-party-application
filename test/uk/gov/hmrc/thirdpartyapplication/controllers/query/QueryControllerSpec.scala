/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers.query

import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.libs.json._
import play.api.test.FakeRequest

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApiIdentifierFixtures
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, PaginatedApplications, StateHistoryFixtures}
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.QueriedApplication
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.Param.{NoSubscriptionsQP, UserIdQP}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models._
import uk.gov.hmrc.thirdpartyapplication.controllers.ControllerSpec
import uk.gov.hmrc.thirdpartyapplication.controllers.query.QueryController
import uk.gov.hmrc.thirdpartyapplication.mocks.QueryServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{CommonApplicationId, StoredApplicationFixtures}

class QueryControllerSpec
    extends ControllerSpec
    with CommonApplicationId
    with ApplicationWithCollaboratorsFixtures
    with StateHistoryFixtures
    with ApiIdentifierFixtures
    with StoredApplicationFixtures {
  import play.api.test.Helpers._
  import play.api.test.Helpers

  implicit lazy val materializer: Materializer = NoMaterializer

  trait Setup extends QueryServiceMockModule {
    val underTest            = new QueryController(QueryServiceMock.aMock, Helpers.stubControllerComponents())
    val appWithCollaborators = storedApp.asAppWithCollaborators
    val appWithSubs          = appWithCollaborators.withSubscriptions(Set(apiIdentifierOne, apiIdentifierTwo))
    val appWithStateHistory  = QueriedApplication(appWithCollaborators.details, appWithCollaborators.collaborators, None, None, stateHistory = Some(List(aStateHistoryTesting)))
  }

  "QueryController" should {
    "work for single query" in new Setup {
      QueryServiceMock.FetchSingleApplicationByQuery.thenReturnsFor(ApplicationQuery.ByClientId(clientIdOne, false, Nil), appWithCollaborators)
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(appWithCollaborators)
    }

    "work for single query with subs" in new Setup {
      QueryServiceMock.FetchSingleApplicationByQuery.thenReturnsFor(ApplicationQuery.ByClientId(clientIdOne, false, Nil, wantSubscriptions = true), appWithSubs)
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientIdOne}&wantSubscriptions"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(appWithSubs)
    }

    "work for single query with state history" in new Setup {
      QueryServiceMock.FetchSingleApplicationByQuery.thenReturnsFor(ApplicationQuery.ByClientId(clientIdOne, false, Nil, wantStateHistory = true), appWithStateHistory)
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientIdOne}&wantStateHistory"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(appWithStateHistory)
    }

    "work for single query finding nothing" in new Setup {
      QueryServiceMock.FetchSingleApplicationByQuery.thenReturnsNoneFor(ApplicationQuery.ByClientId(clientIdOne, false, Nil))
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientIdOne}"))

      status(result) shouldBe NOT_FOUND
      contentAsString(result) should include("No application found for query")
      contentAsString(result) should include("APPLICATION_NOT_FOUND")
    }

    "work for single query with subs finding nothing" in new Setup {
      QueryServiceMock.FetchSingleApplicationByQuery.thenReturnsNoneFor(ApplicationQuery.ByClientId(clientIdOne, false, Nil, wantSubscriptions = true))
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientIdOne}&wantSubscriptions"))

      status(result) shouldBe NOT_FOUND
      contentAsString(result) should include("No application found for query")
      contentAsString(result) should include("APPLICATION_NOT_FOUND")
    }

    "work for general query" in new Setup {
      QueryServiceMock.FetchApplicationsByQuery.thenReturnsFor(
        ApplicationQuery.GeneralOpenEndedApplicationQuery(UserIdQP(userIdOne) :: Nil),
        appWithCollaborators
      )
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?userId=${userIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(List(appWithCollaborators))
    }

    "work for general query for noSubscriptions" in new Setup {
      QueryServiceMock.FetchApplicationsByQuery.thenReturnsFor(
        ApplicationQuery.GeneralOpenEndedApplicationQuery(NoSubscriptionsQP :: Nil),
        appWithCollaborators
      )
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?noSubscriptions="))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(List(appWithCollaborators))
    }

    "work for general query with subs" in new Setup {
      QueryServiceMock.FetchApplicationsByQuery.thenReturnsSubsFor(
        ApplicationQuery.GeneralOpenEndedApplicationQuery(UserIdQP(userIdOne) :: Nil, wantSubscriptions = true),
        appWithSubs
      )
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?userId=${userIdOne}&wantSubscriptions"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(List(appWithSubs))
    }

    "work for general query finding nothing" in new Setup {
      QueryServiceMock.FetchApplicationsByQuery.thenReturnsNoApps()
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?userId=${userIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe JsArray.empty
    }

    "work for general query with subs finding nothing" in new Setup {
      QueryServiceMock.FetchApplicationsByQuery.thenReturnsNoApps()
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?userId=${userIdOne}&wantSubscriptions"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe JsArray.empty
    }

    "work for paginated query using default sort" in new Setup {
      QueryServiceMock.FetchPaginatedApplications.thenReturnsFor(
        PaginatedApplicationQuery(UserIdQP(userIdOne) :: Nil, sorting = Sorting.NoSorting, pagination = Pagination(33)),
        PaginatedApplications(List(appWithCollaborators), 1, 33, 105, 1)
      )

      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?pageSize=33&userId=${userIdOne}"))

      status(result) shouldBe OK
      val expected = PaginatedApplications(List(storedApp.asAppWithCollaborators), 1, 33, 105, 1)
      contentAsJson(result) shouldBe Json.toJson(expected)
    }

    "work for paginated query" in new Setup {
      QueryServiceMock.FetchPaginatedApplications.thenReturnsFor(
        PaginatedApplicationQuery(UserIdQP(userIdOne) :: Nil, sorting = Sorting.LastUseDateAscending, pagination = Pagination(33)),
        PaginatedApplications(List(appWithCollaborators), 1, 33, 105, 1)
      )

      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?pageSize=33&sort=LAST_USE_ASC&userId=${userIdOne}"))

      status(result) shouldBe OK
      val expected = PaginatedApplications(List(storedApp.asAppWithCollaborators), 1, 33, 105, 1)
      contentAsJson(result) shouldBe Json.toJson(expected)
    }

    "work for paginated query finding nothing" in new Setup {
      QueryServiceMock.FetchPaginatedApplications.thenReturnsNoApps(102)

      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?pageNbr=1&userId=${userIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(PaginatedApplications(List.empty, 1, 25, 102, 0))
    }

    "report errors back" in new Setup {
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?userId=ABC&environment=STAGING"))

      status(result) shouldBe BAD_REQUEST
      contentAsJson(result) shouldBe JsObject(Map(
        "code"    -> JsString("INVALID_QUERY"),
        "message" -> JsArray(Seq(JsString("ABC is not a valid user id"), JsString("STAGING is not a valid environment")))
      ))
    }
  }
}
