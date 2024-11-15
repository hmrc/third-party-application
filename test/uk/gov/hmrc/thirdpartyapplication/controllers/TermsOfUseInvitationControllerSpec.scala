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

package uk.gov.hmrc.thirdpartyapplication.controllers

import java.time.temporal.ChronoUnit._
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.http.Status._
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsJson, status, stubControllerComponents}

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationDataServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.services.TermsOfUseInvitationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.EMAIL_SENT
import uk.gov.hmrc.thirdpartyapplication.models.{TermsOfUseInvitationResponse, TermsOfUseInvitationWithApplicationResponse}
import uk.gov.hmrc.thirdpartyapplication.util._

class TermsOfUseInvitationControllerSpec extends ControllerSpec with ApplicationDataServiceMockModule with SubmissionsServiceMockModule with StoredApplicationFixtures
    with SubmissionsTestData {

  trait Setup extends TermsOfUseInvitationServiceMockModule {

    val dueDate = instant.plus(21, DAYS)

    lazy val underTest = new TermsOfUseInvitationController(
      TermsOfUseInvitationServiceMock.aMock,
      ApplicationDataServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      stubControllerComponents()
    )
  }

  "fetch invitation" should {
    "return an OK with a terms of use invitation response" in new Setup {
      val response = TermsOfUseInvitationResponse(applicationId, instant, instant, dueDate, None, EMAIL_SENT)

      TermsOfUseInvitationServiceMock.FetchInvitation.thenReturn(response)

      val result = underTest.fetchInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(response)
    }

    "return an NOT_FOUND when no terms of use invitation exists for the given application id" in new Setup {
      TermsOfUseInvitationServiceMock.FetchInvitation.thenReturnNone

      val result = underTest.fetchInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe NOT_FOUND
    }
  }

  "fetch invitations" should {
    "return terms of use invitations" in new Setup {
      val invitations = List(
        TermsOfUseInvitationResponse(
          applicationId,
          instant,
          instant,
          dueDate,
          None,
          EMAIL_SENT
        )
      )

      TermsOfUseInvitationServiceMock.FetchInvitations.thenReturn(invitations)

      val result = underTest.fetchInvitations()(FakeRequest.apply())

      status(result) shouldBe OK

      val responses = Json.fromJson[List[TermsOfUseInvitationResponse]](contentAsJson(result)).get
      responses.size shouldBe 1
      responses.head.applicationId shouldBe applicationId
    }
  }

  "search invitations" should {
    "return terms of use invitations" in new Setup {
      val invitations = List(
        TermsOfUseInvitationWithApplicationResponse(
          applicationId,
          instant,
          instant,
          dueDate,
          None,
          EMAIL_SENT,
          "Petes App"
        )
      )

      TermsOfUseInvitationServiceMock.Search.thenReturn(invitations)

      val result = underTest.searchInvitations()(FakeRequest("GET", "/terms-of-use/search?status=EMAIL_SENT&status=REMINDER_EMAIL_SENT"))

      status(result) shouldBe OK

      val responses = Json.fromJson[List[TermsOfUseInvitationResponse]](contentAsJson(result)).get
      responses.size shouldBe 1
      responses.head.applicationId shouldBe applicationId
    }
  }
}
