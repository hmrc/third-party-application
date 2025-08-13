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

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, PaginatedApplications}
import uk.gov.hmrc.thirdpartyapplication.controllers.ControllerSpec
import uk.gov.hmrc.thirdpartyapplication.controllers.query.QueryController
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.util.{CommonApplicationId, StoredApplicationFixtures}

class QueryControllerSpec
    extends ControllerSpec
    with CommonApplicationId
    with ApplicationWithCollaboratorsFixtures
    with StoredApplicationFixtures {
  import play.api.test.Helpers._
  import play.api.test.Helpers

  implicit lazy val materializer: Materializer = NoMaterializer

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest            = new QueryController(ApplicationRepoMock.aMock, Helpers.stubControllerComponents())
    val appWithCollaborators = StoredApplication.asApplication(storedApp)
  }

  "QueryController" should {
    "work for single query" in new Setup {
      ApplicationRepoMock.FetchBySingleApplicationQuery.thenReturns(storedApp)
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(StoredApplication.asApplication(storedApp))
    }

    "work for single query finding nothing" in new Setup {
      ApplicationRepoMock.FetchBySingleApplicationQuery.thenReturnsNothing()
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientIdOne}"))

      status(result) shouldBe NOT_FOUND
      contentAsString(result) should include("No application found for query")
      contentAsString(result) should include("APPLICATION_NOT_FOUND")
    }

    "work for general query" in new Setup {
      ApplicationRepoMock.FetchByGeneralOpenEndedApplicationQuery.thenReturns(storedApp)
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?userId=${userIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(List(appWithCollaborators))
    }

    "work for general query finding nothing" in new Setup {
      ApplicationRepoMock.FetchByGeneralOpenEndedApplicationQuery.thenReturns()
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?userId=${userIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe JsArray.empty
    }

    "work for paginated query" in new Setup {
      ApplicationRepoMock.FetchByPaginatedApplicationQuery.thenReturns(105, storedApp)
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?pageSize=25&userId=${userIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(PaginatedApplications(List(appWithCollaborators), 1, 25, 105, 1))
    }

    "work for paginated query finding nothing" in new Setup {
      ApplicationRepoMock.FetchByPaginatedApplicationQuery.thenReturnsNone(102)
      val result = underTest.queryDispatcher()(FakeRequest("GET", s"?pageSize=10&userId=${userIdOne}"))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(PaginatedApplications(List.empty, 1, 10, 102, 0))
    }
  }
}
