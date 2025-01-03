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

package uk.gov.hmrc.thirdpartyapplication.models

import java.time.{ZoneOffset, ZonedDateTime}

import play.api.test.FakeRequest

import uk.gov.hmrc.apiplatform.modules.common.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._

class ApplicationSearchSpec extends HmrcSpec {

  "ApplicationSearch" should {

    "correctly parse page number and size" in {
      val expectedPageNumber: Int = 2
      val expectedPageSize: Int   = 50
      val request                 = FakeRequest("GET", s"/applications?page=$expectedPageNumber&pageSize=$expectedPageSize")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.pageNumber shouldBe expectedPageNumber
      searchObject.pageSize shouldBe expectedPageSize
    }

    "correctly parse search text filter" in {
      val searchText = "foo"
      val request    = FakeRequest("GET", s"/applications?search=$searchText")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters.size shouldBe 1
      searchObject.filters should contain(ApplicationTextSearch)
      searchObject.textToSearch shouldBe Some(searchText)
    }

    "correctly parse API Subscriptions filter" in {
      val request = FakeRequest("GET", "/applications?apiSubscription=ANY")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(OneOrMoreAPISubscriptions)
    }

    "correctly parse Application Status filter" in {
      val request = FakeRequest("GET", "/applications?status=PENDING_GATEKEEPER_CHECK")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(PendingGatekeeperCheck)
    }

    "correctly parse Application Status blocked filter" in {
      val request = FakeRequest("GET", "/applications?status=BLOCKED")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(Blocked)
    }

    "correctly parse Access Type filter" in {
      val request = FakeRequest("GET", "/applications?accessType=PRIVILEGED")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(PrivilegedAccess)
    }

    "correctly parse lastUseBefore into LastUseBeforeDate filter" in {
      val dateAsISOString  = "2020-02-22T16:35:00"
      val expectedDateTime = ZonedDateTime.of(2020, 2, 22, 16, 35, 0, 0, ZoneOffset.UTC).toInstant

      val request = FakeRequest("GET", s"/applications?lastUseBefore=$dateAsISOString")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      val parsedFilter = searchObject.filters.head
      parsedFilter match {
        case LastUseBeforeDate(lastUseDate) => lastUseDate shouldBe expectedDateTime
        case _                              => fail()
      }
    }

    "correctly parse date only into LastUseBeforeDate filter" in {
      val dateAsISOString  = "2020-02-22"
      val expectedDateTime = ZonedDateTime.of(2020, 2, 22, 0, 0, 0, 0, ZoneOffset.UTC).toInstant

      val request = FakeRequest("GET", s"/applications?lastUseBefore=$dateAsISOString")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      val parsedFilter = searchObject.filters.head
      parsedFilter match {
        case LastUseBeforeDate(lastUseDate) => lastUseDate shouldBe expectedDateTime
        case _                              => fail()
      }
    }

    "correctly parse lastUseAfter into LastUseAfterDate filter" in {
      val dateAsISOString  = "2020-02-22T16:35:00Z"
      val expectedDateTime = ZonedDateTime.of(2020, 2, 22, 16, 35, 0, 0, ZoneOffset.UTC).toInstant

      val request = FakeRequest("GET", s"/applications?lastUseAfter=$dateAsISOString")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      val parsedFilter = searchObject.filters.head
      parsedFilter match {
        case LastUseAfterDate(lastUseDate) => lastUseDate shouldBe expectedDateTime
        case _                             => fail()
      }
    }

    "correctly parse date only into LastUseAfterDate filter" in {
      val dateAsISOString = "2020-02-22"

      val expectedDateTime = ZonedDateTime.of(2020, 2, 22, 0, 0, 0, 0, ZoneOffset.UTC).toInstant

      val request = FakeRequest("GET", s"/applications?lastUseAfter=$dateAsISOString")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      val parsedFilter = searchObject.filters.head
      parsedFilter match {
        case LastUseAfterDate(lastUseDate) => lastUseDate shouldBe expectedDateTime
        case _                             => fail()
      }
    }

    "correctly parses multiple filters" in {
      val expectedPageNumber: Int    = 3
      val expectedPageSize: Int      = 250
      val expectedSearchText: String = "foo"
      val expectedAPIContext         = "test-api"
      val expectedAPIVersion         = "v1"

      val request =
        FakeRequest(
          "GET",
          s"/applications" +
            s"?apiSubscription=$expectedAPIContext" +
            s"&apiVersion=$expectedAPIVersion" +
            s"&status=CREATED" +
            s"&accessType=ROPC" +
            s"&search=$expectedSearchText" +
            s"&page=$expectedPageNumber" +
            s"&pageSize=$expectedPageSize"
        )

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(SpecificAPISubscription)
      searchObject.filters should contain(Created)
    }

    "not return a filter where apiSubscription is included with empty string" in {
      val request = FakeRequest("GET", "/applications?apiSubscription=")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters shouldBe empty
    }

    "populate apiContext if specific value is provided" in {
      val api     = "foo"
      val request = FakeRequest("GET", s"/applications?apiSubscription=$api")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.apiContext shouldBe Some(api.asContext)
      searchObject.filters should contain(SpecificAPISubscription)
    }

    "populate apiContext and apiVersion if specific values are provided" in {
      val api        = "foo"
      val apiVersion = "1.0"
      val request    = FakeRequest("GET", s"/applications?apiSubscription=$api--$apiVersion")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(SpecificAPISubscription)
      searchObject.apiContext shouldBe Some(api.asContext)
      searchObject.apiVersion shouldBe Some(apiVersion.asVersion)
    }

    "populate user if specific value is provided" in {
      val user    = UserId.random
      val request = FakeRequest("GET", s"/applications?user=$user")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.userId shouldBe Some(user)
      searchObject.filters should contain(ApplicationUserSearch)
    }

    "populate sort as NameAscending when sort is NAME_ASC" in {
      val request      = FakeRequest("GET", "/applications?sort=NAME_ASC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe NameAscending
    }

    "populate sort as NameDescending when sort is NAME_DESC" in {
      val request      = FakeRequest("GET", "/applications?sort=NAME_DESC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe NameDescending
    }

    "populate sort as SubmittedAscending when sort is SUBMITTED_DESC" in {
      val request      = FakeRequest("GET", "/applications?sort=SUBMITTED_ASC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe SubmittedAscending
    }

    "populate sort as SubmittedDescending when sort is SUBMITTED_DESC" in {
      val request      = FakeRequest("GET", "/applications?sort=SUBMITTED_DESC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe SubmittedDescending
    }

    "populate sort as SubmittedAscending when sort is not specified" in {
      val request      = FakeRequest("GET", "/applications")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe SubmittedAscending
    }
  }
}
