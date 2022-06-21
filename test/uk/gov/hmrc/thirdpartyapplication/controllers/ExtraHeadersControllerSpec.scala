/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers

import play.api.test.FakeRequest
import play.api.mvc.AnyContentAsEmpty
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class ExtraHeadersControllerSpec
    extends ControllerSpec {

  import play.api.test.Helpers

  trait Setup {
    val underTest = new ExtraHeadersController(Helpers.stubControllerComponents()) {}

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

  }

  "hc" should {

    "take the X-email-address and X-name fields from the incoming headers" in new Setup {
      val req: FakeRequest[AnyContentAsEmpty.type] = request.withHeaders(
        LOGGED_IN_USER_NAME_HEADER  -> "John Smith",
        LOGGED_IN_USER_EMAIL_HEADER -> "test@example.com",
        X_REQUEST_ID_HEADER         -> "requestId"
      )

      underTest.hc(req).headers(Seq(LOGGED_IN_USER_NAME_HEADER)) should contain(LOGGED_IN_USER_NAME_HEADER -> "John Smith")
      underTest.hc(req).headers(Seq(LOGGED_IN_USER_EMAIL_HEADER)) should contain(LOGGED_IN_USER_EMAIL_HEADER -> "test@example.com")
      underTest.hc(req).headers(Seq(X_REQUEST_ID_HEADER)) should contain(X_REQUEST_ID_HEADER -> "requestId")
    }

    "contain each header if only one exists" in new Setup {
      val nameHeader: (String, String)  = LOGGED_IN_USER_NAME_HEADER  -> "John Smith"
      val emailHeader: (String, String) = LOGGED_IN_USER_EMAIL_HEADER -> "test@example.com"

      underTest.hc(request.withHeaders(nameHeader)).headers(Seq(LOGGED_IN_USER_NAME_HEADER)) should contain(nameHeader)
      underTest.hc(request.withHeaders(emailHeader)).headers(Seq(LOGGED_IN_USER_EMAIL_HEADER)) should contain(emailHeader)
    }
  }
}
