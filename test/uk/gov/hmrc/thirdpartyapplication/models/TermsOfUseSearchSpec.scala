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

import play.api.test.FakeRequest

import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec

class TermsOfUseSearchSpec extends HmrcSpec {

  "TermsOfUseSearch" should {

    "correctly parse search string and multiple statuses" in {
      val request = FakeRequest("GET", "/terms-of-use/search?status=EMAIL_SENT&status=REMINDER_EMAIL_SENT&search=Bob")

      val searchObject = TermsOfUseSearch.fromQueryString(request.queryString)

      searchObject.filters.size shouldBe 3
      searchObject.textToSearch shouldBe Some("Bob")
      searchObject.filters shouldBe (List(EmailSent, ReminderEmailSent, TermsOfUseTextSearch))
    }

    "correctly parse no statuses" in {
      val request = FakeRequest("GET", "/terms-of-use/search")

      val searchObject = TermsOfUseSearch.fromQueryString(request.queryString)

      searchObject.filters.size shouldBe 0
      searchObject.textToSearch.isDefined shouldBe false
    }
  }
}
