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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers

import scala.concurrent.ExecutionContext.Implicits.global

import akka.stream.testkit.NoMaterializer

import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.{GrantApprovalsServiceMockModule, RequestApprovalsServiceMockModule}
import uk.gov.hmrc.apiplatform.modules.approvals.services._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationState
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationDataServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}

class ApprovalsControllerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData with FixedClock {
  implicit val mat = NoMaterializer
  val name         = "bob example"
  val emailAddress = "test@example.com"
  val appId        = ApplicationId.random

  trait Setup
      extends RequestApprovalsServiceMockModule
      with GrantApprovalsServiceMockModule
      with ApplicationDataServiceMockModule
      with SubmissionsServiceMockModule {

    val underTest = new ApprovalsController(
      ApplicationDataServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      RequestApprovalsServiceMock.aMock,
      GrantApprovalsServiceMock.aMock,
      Helpers.stubControllerComponents()
    )

    def hasApp   = ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(appId, state = ApplicationState.testing))
    def hasNoApp = ApplicationDataServiceMock.FetchApp.thenReturnNone

    def hasNoSubmission  = SubmissionsServiceMock.FetchLatest.thenReturnNone
    def hasSubmission    = SubmissionsServiceMock.FetchLatest.thenReturn(aSubmission.hasCompletelyAnswered)
    def hasExtSubmission = SubmissionsServiceMock.FetchLatestExtended.thenReturn(aSubmission.hasCompletelyAnswered.withCompletedProgresss())
  }

  "requestApproval" should {
    implicit val writes = Json.writes[ApprovalsController.RequestApprovalRequest]
    val jsonBody        = Json.toJson(ApprovalsController.RequestApprovalRequest(name, emailAddress))
    val request         = FakeRequest().withJsonBody(jsonBody)

    "return 'not found' error response if application is missing" in new Setup {
      hasNoApp
      hasNoSubmission

      val result = underTest.requestApproval(appId)(request)

      status(result) shouldBe NOT_FOUND
    }

    "return 'no content' success response if request is approved" in new Setup {
      hasApp
      hasSubmission
      RequestApprovalsServiceMock.RequestApproval.thenRequestIsApprovedFor(appId, emailAddress)
      val result = underTest.requestApproval(appId)(request)

      status(result) shouldBe OK
    }

    "return 'precondition failed' error response if request is not in the correct state" in new Setup {
      hasApp
      hasSubmission
      RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithInvalidStateTransitionErrorFor(appId, emailAddress)

      val result = underTest.requestApproval(appId)(request)

      status(result) shouldBe PRECONDITION_FAILED
    }

    "return 'not found' error response if submission is missing" in new Setup {
      hasApp
      hasNoSubmission

      val result = underTest.requestApproval(appId)(request)

      status(result) shouldBe NOT_FOUND
    }

    "return 'precondition failed' error response if submission is incomplete" in new Setup {
      hasApp
      hasSubmission
      RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithIncorrectSubmissionErrorFor(appId, emailAddress)
      val result = underTest.requestApproval(appId)(request)

      status(result) shouldBe PRECONDITION_FAILED
    }

    "return 'conflict' error response if application with same name already exists" in new Setup {
      hasApp
      hasSubmission

      RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithApplicationNameAlreadyExistsErrorFor(appId, emailAddress)
      val result = underTest.requestApproval(appId)(request)

      status(result) shouldBe CONFLICT
    }

    "return 'precondition failed' error response if name is illegal" in new Setup {
      hasApp
      hasSubmission
      RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithIllegalNameErrorFor(appId, emailAddress)
      val result = underTest.requestApproval(appId)(request)

      status(result) shouldBe PRECONDITION_FAILED
    }
  }

  "grant" should {
    implicit val writes = Json.writes[ApprovalsController.GrantedRequest]
    val jsonBody        = Json.toJson(ApprovalsController.GrantedRequest("Bob from SDST", None, None))
    val request         = FakeRequest().withJsonBody(jsonBody)
    val application     = anApplicationData(appId, pendingGatekeeperApprovalState("bob"))

    "return 'no content' success response if request is declined" in new Setup {
      hasApp
      hasSubmission
      GrantApprovalsServiceMock.Grant.thenReturn(GrantApprovalsService.Actioned(application))
      val result = underTest.grant(appId)(request)

      status(result) shouldBe OK
    }
  }

  "grant with warnings" should {
    implicit val writes = Json.writes[ApprovalsController.GrantedRequest]
    val jsonBody        = Json.toJson(ApprovalsController.GrantedRequest("Bob from SDST", Some("This is a warning"), Some("Marty McFly")))
    val request         = FakeRequest().withJsonBody(jsonBody)
    val application     = anApplicationData(appId, pendingGatekeeperApprovalState("bob"))

    "return 'no content' success response if request is granted with warnings" in new Setup {
      hasApp
      hasSubmission
      GrantApprovalsServiceMock.Grant.thenReturn(GrantApprovalsService.Actioned(application))
      val result = underTest.grant(appId)(request)

      status(result) shouldBe OK
    }
  }
}
