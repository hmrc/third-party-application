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

import org.scalatest.prop.TableDrivenPropertyChecks._

import play.api.test.FakeRequest

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.models.DeleteRestrictionFilter.DoNotDelete

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

    "correctly parse ANY API Subscriptions filter" in {
      val request = FakeRequest("GET", "/applications?apiSubscription=ANY")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(APISubscriptionFilter.OneOrMoreAPISubscriptions)
    }

    "correctly parse NONE API Subscriptions filter" in {
      val request = FakeRequest("GET", "/applications?apiSubscription=NONE")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(APISubscriptionFilter.NoAPISubscriptions)
    }

    "correctly parse Application Status filters" in {
      val scenarios = Table(
        ("status", "result"),
        ("CREATED", StatusFilter.Created),
        ("PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION", StatusFilter.PendingResponsibleIndividualVerification),
        ("PENDING_GATEKEEPER_CHECK", StatusFilter.PendingGatekeeperCheck),
        ("PENDING_SUBMITTER_VERIFICATION", StatusFilter.PendingSubmitterVerification),
        ("ACTIVE", StatusFilter.Active),
        ("DELETED", StatusFilter.WasDeleted),
        ("EXCLUDING_DELETED", StatusFilter.ExcludingDeleted),
        ("BLOCKED", StatusFilter.Blocked),
        ("ALL", StatusFilter.NoFiltering)
      )

      forAll(scenarios) { (testStatus: String, testResult: StatusFilter) =>
        {
          val request = FakeRequest("GET", s"/applications?status=$testStatus")
          val result  = ApplicationSearch.fromQueryString(request.queryString)
          result.filters should contain(testResult)
        }
      }
    }

    "correctly parse Access Type filters" in {
      val scenarios = Table(
        ("access type", "result"),
        ("STANDARD", AccessTypeFilter.StandardAccess),
        ("PRIVILEGED", AccessTypeFilter.PrivilegedAccess),
        ("ROPC", AccessTypeFilter.ROPCAccess),
        ("ALL", AccessTypeFilter.NoFiltering)
      )

      forAll(scenarios) { (testAccessType: String, testResult: AccessTypeFilter) =>
        {
          val request = FakeRequest("GET", s"/applications?accessType=$testAccessType")
          val result  = ApplicationSearch.fromQueryString(request.queryString)
          result.filters should contain(testResult)
        }
      }
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

    "correctly parse delete restriction filter" in {
      val request = FakeRequest("GET", s"/applications?deleteRestriction=DO_NOT_DELETE")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(DoNotDelete)
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

      searchObject.filters should contain(APISubscriptionFilter.SpecificAPISubscription)
      searchObject.filters should contain(StatusFilter.Created)
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
      searchObject.filters should contain(APISubscriptionFilter.SpecificAPISubscription)
    }

    "populate apiContext and apiVersion if specific values are provided" in {
      val api        = "foo"
      val apiVersion = "1.0"
      val request    = FakeRequest("GET", s"/applications?apiSubscription=$api--$apiVersion")

      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.filters should contain(APISubscriptionFilter.SpecificAPISubscription)
      searchObject.apiContext shouldBe Some(api.asContext)
      searchObject.apiVersion shouldBe Some(apiVersion.asVersion)
    }

    "populate sort as NameAscending when sort is NAME_ASC" in {
      val request      = FakeRequest("GET", "/applications?sort=NAME_ASC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe ApplicationSort.NameAscending
    }

    "populate sort as NameDescending when sort is NAME_DESC" in {
      val request      = FakeRequest("GET", "/applications?sort=NAME_DESC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe ApplicationSort.NameDescending
    }

    "populate sort as SubmittedAscending when sort is SUBMITTED_DESC" in {
      val request      = FakeRequest("GET", "/applications?sort=SUBMITTED_ASC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe ApplicationSort.SubmittedAscending
    }

    "populate sort as SubmittedDescending when sort is SUBMITTED_DESC" in {
      val request      = FakeRequest("GET", "/applications?sort=SUBMITTED_DESC")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe ApplicationSort.SubmittedDescending
    }

    "populate sort as SubmittedAscending when sort is not specified" in {
      val request      = FakeRequest("GET", "/applications")
      val searchObject = ApplicationSearch.fromQueryString(request.queryString)

      searchObject.sort shouldBe ApplicationSort.SubmittedAscending
    }
  }
}
