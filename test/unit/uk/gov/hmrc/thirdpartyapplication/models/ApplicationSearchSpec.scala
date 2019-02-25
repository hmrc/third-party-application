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

import org.scalatest.mockito.MockitoSugar
import play.api.test.FakeRequest
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.models._


class ApplicationSearchSpec extends UnitSpec with WithFakeApplication with MockitoSugar {

  "ApplicationSearch" should {
    "correctly parse page number and size" in {
      val expectedPageNumber: Int = 2
      val expectedPageSize: Int = 50
      val request = FakeRequest("GET", s"/applications?page=$expectedPageNumber&pageSize=$expectedPageSize")

      val searchObject = new ApplicationSearch(request)

      assert(searchObject.pageNumber == expectedPageNumber)
      assert(searchObject.pageSize == expectedPageSize)
      assert(searchObject.filters.isEmpty)
    }

    "correctly parse API Subscriptions filter" in {
      val expectedPageNumber: Int = 1
      val expectedPageSize: Int = 100
      val request = FakeRequest("GET", s"/applications?apiSubscriptions=ANYSUB&page=$expectedPageNumber&pageSize=$expectedPageSize")

      val searchObject = new ApplicationSearch(request)

      assert(searchObject.pageNumber == expectedPageNumber)
      assert(searchObject.pageSize == expectedPageSize)
      assert(searchObject.filters.size == 1)
      assert(searchObject.filters.contains(OneOrMoreAPISubscriptions))
    }

    "correctly parse Application Status filter" in {
      val expectedPageNumber: Int = 1
      val expectedPageSize: Int = 100
      val request = FakeRequest("GET", s"/applications?status=PENDING_GATEKEEPER_CHECK&page=$expectedPageNumber&pageSize=$expectedPageSize")

      val searchObject = new ApplicationSearch(request)

      assert(searchObject.pageNumber == expectedPageNumber)
      assert(searchObject.pageSize == expectedPageSize)
      assert(searchObject.filters.size == 1)
      assert(searchObject.filters.contains(PendingGatekeeperCheck))
    }

    "correctly parse Terms of Use filter" in {
      val expectedPageNumber: Int = 1
      val expectedPageSize: Int = 100
      val request = FakeRequest("GET", s"/applications?termsOfUse=TOU_NOT_ACCEPTED&page=$expectedPageNumber&pageSize=$expectedPageSize")

      val searchObject = new ApplicationSearch(request)

      assert(searchObject.pageNumber == expectedPageNumber)
      assert(searchObject.pageSize == expectedPageSize)
      assert(searchObject.filters.size == 1)
      assert(searchObject.filters.contains(TermsOfUseNotAccepted))
    }

    "correctly parse Access Type filter" in {
      val expectedPageNumber: Int = 1
      val expectedPageSize: Int = 100
      val request = FakeRequest("GET", s"/applications?accessType=ACCESS_TYPE_PRIVILEGED&page=$expectedPageNumber&pageSize=$expectedPageSize")

      val searchObject = new ApplicationSearch(request)

      assert(searchObject.pageNumber == expectedPageNumber)
      assert(searchObject.pageSize == expectedPageSize)
      assert(searchObject.filters.size == 1)
      assert(searchObject.filters.contains(PrivilegedAccess))
    }

    "correctly parses multiple filters" in {
      val expectedPageNumber: Int = 1
      val expectedPageSize: Int = 100
      val request =
        FakeRequest(
          "GET",
          s"/applications" +
            s"?apiSubscriptions=NOSUB" +
            s"&status=CREATED" +
            s"&termsOfUse=TOU_ACCEPTED" +
            s"&accessType=ACCESS_TYPE_ROPC" +
            s"&page=$expectedPageNumber" +
            s"&pageSize=$expectedPageSize")

      val searchObject = new ApplicationSearch(request)

      assert(searchObject.pageNumber == expectedPageNumber)
      assert(searchObject.pageSize == expectedPageSize)
      assert(searchObject.filters.contains(NoAPISubscriptions))
      assert(searchObject.filters.contains(Created))
      assert(searchObject.filters.contains(TermsOfUseAccepted))
      assert(searchObject.filters.contains(ROPCAccess))
    }
  }
}
