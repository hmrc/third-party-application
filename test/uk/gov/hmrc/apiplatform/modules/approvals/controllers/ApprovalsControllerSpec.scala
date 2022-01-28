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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.test.Helpers._

import play.api.test.Helpers
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.RequestApprovalsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.DeclineApprovalsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import play.api.test.FakeRequest
import akka.stream.testkit.NoMaterializer
import play.api.libs.json.Json

class ApprovalsControllerSpec extends AsyncHmrcSpec {
    implicit val mat = NoMaterializer
    implicit val writes = Json.writes[ApprovalsController.RequestApprovalRequest]
    val emailAddress = "test@example.com"
    val appId = ApplicationId.random
    
    trait Setup extends RequestApprovalsServiceMockModule with DeclineApprovalsServiceMockModule {
        val jsonBody = Json.toJson(ApprovalsController.RequestApprovalRequest(emailAddress))
        val request = FakeRequest().withBody(jsonBody)

        val underTest = new ApprovalsController(RequestApprovalsServiceMock.aMock, DeclineApprovalsServiceMock.aMock, Helpers.stubControllerComponents())
    }

    "requestApproval" should {
        "return 'no content' success response if request is approved" in new Setup {
            RequestApprovalsServiceMock.RequestApproval.thenRequestIsApprovedFor(appId, emailAddress)
            val result = underTest.requestApproval(appId)(request)

            status(result) shouldBe OK
        }        

        "return 'precondition failed' error response if request is not in the correct state" in new Setup {
            RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithInvalidStateTransitionErrorFor(appId, emailAddress)
            val result = underTest.requestApproval(appId)(request)

            status(result) shouldBe PRECONDITION_FAILED
        }

        "return 'conflict' error response if application already exists" in new Setup {
            RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithApplicationAlreadyExistsErrorFor(appId, emailAddress)
            val result = underTest.requestApproval(appId)(request)

            status(result) shouldBe CONFLICT
        }

        "return 'bad request' error response if application already exists" in new Setup {
            RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithApplicationDoesNotExistErrorFor(appId, emailAddress)
            val result = underTest.requestApproval(appId)(request)

            status(result) shouldBe BAD_REQUEST
        }

        "return 'precondition failed' error response if submission is incomplete" in new Setup {
            RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithIncompleteSubmissionErrorFor(appId, emailAddress)
            val result = underTest.requestApproval(appId)(request)

            status(result) shouldBe PRECONDITION_FAILED
        }        

        "return 'precondition failed' error response if name is illegal" in new Setup {
            RequestApprovalsServiceMock.RequestApproval.thenRequestFailsWithIllegalNameErrorFor(appId, emailAddress)
            val result = underTest.requestApproval(appId)(request)

            status(result) shouldBe PRECONDITION_FAILED
        }                
    }
}