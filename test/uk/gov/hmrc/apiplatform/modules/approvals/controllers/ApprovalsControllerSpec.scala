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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.libs.json.{Json, OWrites}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.GrantApprovalsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.approvals.services._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationStateExamples
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationDataServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ApprovalsControllerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {
  implicit val mat: Materializer = NoMaterializer
  val name                       = "bob example"
  val emailAddress               = "test@example.com"
  val appId                      = ApplicationId.random

  trait Setup
      extends GrantApprovalsServiceMockModule
      with ApplicationDataServiceMockModule
      with SubmissionsServiceMockModule {

    val underTest = new ApprovalsController(
      ApplicationDataServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      GrantApprovalsServiceMock.aMock,
      Helpers.stubControllerComponents()
    )

    def hasApp   = ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(appId, state = ApplicationStateExamples.testing))
    def hasNoApp = ApplicationDataServiceMock.FetchApp.thenReturnNone

    def hasNoSubmission  = SubmissionsServiceMock.FetchLatest.thenReturnNone()
    def hasSubmission    = SubmissionsServiceMock.FetchLatest.thenReturn(aSubmission.hasCompletelyAnswered)
    def hasExtSubmission = SubmissionsServiceMock.FetchLatestExtended.thenReturn(aSubmission.hasCompletelyAnswered.withCompletedProgresss())
  }

  "declineForTouUplift" should {
    implicit val writes: OWrites[ApprovalsController.TouUpliftRequest] = Json.writes[ApprovalsController.TouUpliftRequest]
    val jsonBody                                                       = Json.toJson(ApprovalsController.TouUpliftRequest("Bob from SDST", "This is a warning"))
    val request                                                        = FakeRequest().withJsonBody(jsonBody)
    val application                                                    = anApplicationData(appId, productionState("bob"))

    "return 'no content' success response if successful" in new Setup {
      hasApp
      hasSubmission
      GrantApprovalsServiceMock.DeclineForTouUplift.thenReturn(GrantApprovalsService.Actioned(application))
      val result = underTest.declineForTouUplift(appId)(request)

      status(result) shouldBe OK
    }
  }

  "grantWithWarningsForTouUplift" should {
    implicit val writes: OWrites[ApprovalsController.TouUpliftRequest] = Json.writes[ApprovalsController.TouUpliftRequest]
    val jsonBody                                                       = Json.toJson(ApprovalsController.TouUpliftRequest("Bob from SDST", "This is a warning"))
    val request                                                        = FakeRequest().withJsonBody(jsonBody)
    val application                                                    = anApplicationData(appId, productionState("bob"))

    "return 'no content' success response if successful" in new Setup {
      hasApp
      hasSubmission
      GrantApprovalsServiceMock.GrantWithWarningsForTouUplift.thenReturn(GrantApprovalsService.Actioned(application))
      val result = underTest.grantWithWarningsForTouUplift(appId)(request)

      status(result) shouldBe OK
    }
  }

  "resetForTouUplift" should {
    implicit val writes: OWrites[ApprovalsController.TouUpliftRequest] = Json.writes[ApprovalsController.TouUpliftRequest]
    val jsonBody                                                       = Json.toJson(ApprovalsController.TouUpliftRequest("Bob from SDST", "This is a warning"))
    val request                                                        = FakeRequest().withJsonBody(jsonBody)
    val application                                                    = anApplicationData(appId, productionState("bob"))

    "return 'no content' success response if successful" in new Setup {
      hasApp
      hasSubmission
      GrantApprovalsServiceMock.ResetForTouUplift.thenReturn(GrantApprovalsService.Actioned(application))
      val result = underTest.resetForTouUplift(appId)(request)

      status(result) shouldBe OK
    }
  }
}
