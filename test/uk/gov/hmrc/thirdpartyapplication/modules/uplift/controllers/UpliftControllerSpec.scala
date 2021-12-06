/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.uplift.controllers

import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import org.scalatest.prop.TableDrivenPropertyChecks
import akka.stream.Materializer
import akka.stream.testkit.NoMaterializer

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.controllers.ControllerSpec
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode
import uk.gov.hmrc.thirdpartyapplication.controllers.ControllerTestData

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpliftRequested
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationAlreadyExists
import uk.gov.hmrc.thirdpartyapplication.models.InvalidStateTransition
import uk.gov.hmrc.thirdpartyapplication.mocks.UpliftServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.modules.uplift.controllers.UpliftController._

class UpliftControllerSpec 
  extends ControllerSpec
  with ApplicationStateUtil
  with TableDrivenPropertyChecks
  with UpliftServiceMockModule
  with ControllerTestData {

  import play.api.test.Helpers._
  import play.api.test.Helpers

  implicit lazy val materializer: Materializer = NoMaterializer


  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    def canDeleteApplications() = true
    def enabled() = true

    val underTest = new UpliftController(
      UpliftServiceMock.aMock,
      Helpers.stubControllerComponents()
    )
  }

  "requestUplift" should {
    val applicationId = ApplicationId.random
    val requestedByEmailAddress = "big.boss@example.com"
    val requestedName = "Application Name"
    val upliftRequest = UpliftApplicationRequest(requestedName, requestedByEmailAddress)

    "return updated application if successful" in new Setup {
      aNewApplicationResponse().copy(state = pendingGatekeeperApprovalState(requestedByEmailAddress))

      UpliftServiceMock.RequestUplift.thenReturn(UpliftRequested)

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      status(result) shouldBe NO_CONTENT
    }

    "return 404 if the application doesn't exist" in new Setup {

      UpliftServiceMock.RequestUplift.thenFailsWith(new NotFoundException("application doesn't exist"))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 409 (conflict) when an application already exists for that application name" in new Setup {

      UpliftServiceMock.RequestUplift.thenFailsWith(ApplicationAlreadyExists("applicationName"))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, CONFLICT, ErrorCode.APPLICATION_ALREADY_EXISTS)
    }

    "fail with 412 (Precondition Failed) when the application is not in the TESTING state" in new Setup {
      UpliftServiceMock.RequestUplift.thenFailsWith(new InvalidStateTransition(State.PRODUCTION, State.PENDING_GATEKEEPER_APPROVAL, State.TESTING))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, PRECONDITION_FAILED, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      UpliftServiceMock.RequestUplift.thenFailsWith(new RuntimeException("Expected test failure"))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, INTERNAL_SERVER_ERROR, ErrorCode.UNKNOWN_ERROR)
    }
  }

  "verifyUplift" should {

    "verify uplift successfully" in new Setup {
      val verificationCode = "aVerificationCode"

      UpliftServiceMock.VerifyUplift.thenSucceeds()

      val result = underTest.verifyUplift(verificationCode)(request)
      status(result) shouldBe NO_CONTENT
    }

    "verify uplift failed" in new Setup {
      val verificationCode = "aVerificationCode"

      UpliftServiceMock.VerifyUplift.thenFailWithInvalidUpliftVerificationCode()

      val result = underTest.verifyUplift(verificationCode)(request)
      status(result) shouldBe BAD_REQUEST
    }
  }


}