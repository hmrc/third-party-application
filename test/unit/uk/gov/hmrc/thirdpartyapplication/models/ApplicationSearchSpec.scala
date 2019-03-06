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

package unit.uk.gov.hmrc.thirdpartyapplication.models

import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import play.api.test.FakeRequest
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.models._


class ApplicationSearchSpec extends UnitSpec with WithFakeApplication with MockitoSugar with Matchers {

  "ApplicationSearch" should {
    "set appropriate defaults for no-arg constructor" in {
      checkCreatedSearchObject(new ApplicationSearch(), expectedFilters =  Set.empty)
    }

    "set appropriate defaults for paging values" in {
      val searchObject = new ApplicationSearch(filters = Seq(OneOrMoreAPISubscriptions, ROPCAccess))

      checkCreatedSearchObject(searchObject, expectedFilters = Set(OneOrMoreAPISubscriptions, ROPCAccess))
    }

    "correctly parse page number and size" in {
      val expectedPageNumber: Int = 2
      val expectedPageSize: Int = 50
      val request = FakeRequest("GET", s"/applications?page=$expectedPageNumber&pageSize=$expectedPageSize")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(searchObject, expectedPageNumber, expectedPageSize, Set.empty)
    }

    "correctly parse search text filter" in {
      val searchText = "foo"
      val request = FakeRequest("GET", s"/applications?search=$searchText")

      val searchObject = ApplicationSearch.fromRequest(request)

      searchObject.filters.size shouldBe 1
      assert(searchObject.filters.contains(ApplicationTextSearch))
      searchObject.textToSearch shouldBe searchText
    }

    "correctly parse API Subscriptions filter" in {
      val request = FakeRequest("GET", s"/applications?apiSubscription=ANY")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(searchObject, expectedFilters = Set(OneOrMoreAPISubscriptions))
    }

    "correctly parse Application Status filter" in {
      val request = FakeRequest("GET", s"/applications?status=PENDING_GATEKEEPER_CHECK")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(searchObject, expectedFilters = Set(PendingGatekeeperCheck))
    }

    "correctly parse Terms of Use filter" in {
      val request = FakeRequest("GET", s"/applications?termsOfUse=NOT_ACCEPTED")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(searchObject, expectedFilters = Set(TermsOfUseNotAccepted))
    }

    "correctly parse Access Type filter" in {
      val request = FakeRequest("GET", s"/applications?accessType=PRIVILEGED")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(searchObject, expectedFilters = Set(PrivilegedAccess))
    }

    "correctly parses multiple filters" in {
      val expectedPageNumber: Int = 3
      val expectedPageSize: Int = 250
      val expectedSearchText: String = "foo"
      val expectedAPIContext = "test-api"
      val expectedAPIVersion = "v1"

      val request =
        FakeRequest(
          "GET",
          s"/applications" +
            s"?apiSubscription=$expectedAPIContext" +
            s"&apiVersion=$expectedAPIVersion" +
            s"&status=CREATED" +
            s"&termsOfUse=ACCEPTED" +
            s"&accessType=ROPC" +
            s"&search=$expectedSearchText" +
            s"&page=$expectedPageNumber" +
            s"&pageSize=$expectedPageSize")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(
        searchObject,
        expectedPageNumber,
        expectedPageSize,
        Set(SpecificAPISubscription, Created, TermsOfUseAccepted, ROPCAccess, ApplicationTextSearch),
        expectedSearchText,
        expectedAPIContext,
        expectedAPIVersion)
    }

    "not return a filter where apiSubscription is included with empty string" in {
      val request = FakeRequest("GET", "/applications?apiSubscription=")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(searchObject, expectedFilters = Set.empty)
    }

    "populate apiContext if specific value is provided" in {
      val api = "foo"
      val request = FakeRequest("GET", s"/applications?apiSubscription=$api")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(
        searchObject, expectedFilters = Set(SpecificAPISubscription), expectedAPIContext = api)
    }

    "populate apiContext and apiVersion if specific values are provided" in {
      val api = "foo"
      val apiVersion = "1.0"
      val request = FakeRequest("GET", s"/applications?apiSubscription=$api&apiVersion=$apiVersion")

      val searchObject = ApplicationSearch.fromRequest(request)

      checkCreatedSearchObject(
        searchObject,
        expectedFilters = Set(SpecificAPISubscription),
        expectedAPIContext = api,
        expectedAPIVersion = apiVersion)
    }
  }

  def checkCreatedSearchObject(searchObject: ApplicationSearch,
                               expectedPageNumber: Int = 1,
                               expectedPageSize: Int = Int.MaxValue,
                               expectedFilters: Set[ApplicationSearchFilter] = Set.empty,
                               expectedSearchText: String = "",
                               expectedAPIContext: String = "",
                               expectedAPIVersion: String = ""): Unit = {
    searchObject.pageNumber shouldEqual expectedPageNumber
    searchObject.pageSize shouldEqual expectedPageSize
    searchObject.filters.size shouldEqual expectedFilters.size
    searchObject.filters.toSet shouldEqual expectedFilters
    searchObject.textToSearch shouldEqual expectedSearchText
    searchObject.apiContext shouldEqual expectedAPIContext
    searchObject.apiVersion shouldEqual expectedAPIVersion
  }
}
