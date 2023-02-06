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

import java.time.Instant
import java.time.temporal.ChronoUnit._
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.http.Status._
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsJson, status, stubControllerComponents}

import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.mocks.services.TermsOfUseServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationDataServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationState
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData

class TermsOfUseInvitationControllerSpec extends ControllerSpec with ApplicationDataServiceMockModule with SubmissionsServiceMockModule with ApplicationTestData with SubmissionsTestData {

  trait Setup extends TermsOfUseServiceMockModule {
    val applicationId = ApplicationId.random
    val now           = Instant.now().truncatedTo(MILLIS)
    val dueDate       = now.plus(60, DAYS)

    lazy val underTest = new TermsOfUseInvitationController(
      TermsOfUseServiceMock.aMock,
      ApplicationDataServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      stubControllerComponents()
    )
  }

  "create invitation" should {
    "return NOT_FOUND when an application for the given id is not found" in new Setup {
      ApplicationDataServiceMock.FetchApp.thenReturnNone
      
      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe NOT_FOUND
    }

    "return CREATED when an application exists with no submission and a terms of use invitation is created" in new Setup {
      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationState.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      TermsOfUseServiceMock.FetchInvitation.thenReturnNone
      TermsOfUseServiceMock.CreateInvitations.thenReturnSuccess()

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe CREATED
    }

    "return CONFLICT when a terms of use invitation already exists for the application" in new Setup {
      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationState.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      val response = TermsOfUseInvitationResponse(
        applicationId,
        now,
        now,
        dueDate,
        None
      )

      TermsOfUseServiceMock.FetchInvitation.thenReturn(response)

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe CONFLICT
    }

    "return CONFLICT when an application exists with a submission" in new Setup {
      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationState.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturn(aSubmission.hasCompletelyAnswered)

      TermsOfUseServiceMock.FetchInvitation.thenReturnNone
      TermsOfUseServiceMock.CreateInvitations.thenReturnSuccess()

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe CONFLICT
    }

    "return INTERNAL_SERVER_ERROR when a terms of use invitation is NOT created" in new Setup {
      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationState.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      TermsOfUseServiceMock.FetchInvitation.thenReturnNone
      TermsOfUseServiceMock.CreateInvitations.thenFail()

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  "fetch invitation" should {
    "return an OK with a terms of use invitation response" in new Setup {
      val response = TermsOfUseInvitationResponse(applicationId, now, now, dueDate, None)

      TermsOfUseServiceMock.FetchInvitation.thenReturn(response)

      val result = underTest.fetchInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(response)
    }

    "return an NOT_FOUND when no terms of use invitation exists for the given application id" in new Setup {
      TermsOfUseServiceMock.FetchInvitation.thenReturnNone

      val result = underTest.fetchInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe NOT_FOUND
    }
  }

  "fetch invitations" should {
    "return terms of use invitations" in new Setup {
      val invitations = List(
        TermsOfUseInvitationResponse(
          applicationId,
          now,
          now,
          dueDate,
          None
        )
      )

      TermsOfUseServiceMock.FetchInvitations.thenReturn(invitations)

      val result = underTest.fetchInvitations()(FakeRequest.apply())

      status(result) shouldBe OK

      val responses = Json.fromJson[List[TermsOfUseInvitationResponse]](contentAsJson(result)).get
      responses.size shouldBe 1
      responses.head.applicationId shouldBe applicationId
    }
  }
}
