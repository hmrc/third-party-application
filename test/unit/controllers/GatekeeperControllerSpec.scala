/*
 * Copyright 2018 HM Revenue & Customs
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

package controllers

import java.util.UUID

import common.uk.gov.hmrc.common.LogSuppressing
import common.uk.gov.hmrc.testutils.ApplicationStateUtil
import org.apache.http.HttpStatus._
import org.joda.time.DateTime
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result}
import play.api.test.FakeRequest
import uk.gov.hmrc.connector.AuthConnector
import uk.gov.hmrc.controllers.ErrorCode._
import uk.gov.hmrc.controllers.{ErrorCode, _}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.models.ActorType._
import uk.gov.hmrc.models.JsonFormatters._
import uk.gov.hmrc.models.State._
import uk.gov.hmrc.models._
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.services.{ApplicationService, GatekeeperService}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.Future.{failed, successful}

class GatekeeperControllerSpec extends UnitSpec with ScalaFutures with MockitoSugar with WithFakeApplication
  with ApplicationStateUtil with LogSuppressing {

  val authTokenHeader = "authorization" -> "authorizationToken"
  implicit lazy val materializer = fakeApplication.materializer
  implicit lazy val request = FakeRequest()

  trait Setup {
    val mockGatekeeperService = mock[GatekeeperService]
    val mockAuthConnector = mock[AuthConnector]
    val mockApplicationService = mock[ApplicationService]
    implicit val headers = HeaderCarrier()

    val underTest = new GatekeeperController(mockAuthConnector, mockApplicationService, mockGatekeeperService) {
      override implicit def hc(implicit request: RequestHeader): HeaderCarrier = headers
    }

    when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))
  }

  def verifyUnauthorized(result: Result): Unit = {
    status(result) shouldBe 401
    jsonBodyOf(result) shouldBe Json.obj(
      "code" -> "UNAUTHORIZED", "message" -> "Action requires authority: 'api:gatekeeper'"
    )
  }

  "Fetch apps" should {
    "return unauthorised when the user is not authorised" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(false))

      val result = await(underTest.fetchAppsForGatekeeper(request))

      verifyZeroInteractions(mockGatekeeperService)
      verifyUnauthorized(result)
    }

    "return apps" in new Setup {
      val expected = Seq(anAppResult(), anAppResult(state = productionState("user1")))
      when(mockGatekeeperService.fetchNonTestingAppsWithSubmittedDate()).thenReturn(successful(expected))

      val result = await(underTest.fetchAppsForGatekeeper(request))

      jsonBodyOf(result) shouldBe Json.toJson(expected)
    }
  }

  "Fetch app by id" should {
    val appId = UUID.randomUUID()

    "return unauthorised when the user is not authorised" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(false))

      val result = await(underTest.fetchAppById(appId)(request))

      verifyZeroInteractions(mockGatekeeperService)
      verifyUnauthorized(result)
    }

    "return app with history" in new Setup {
      val expected = ApplicationWithHistory(anAppResponse(appId), Seq(aHistory(appId), aHistory(appId, PRODUCTION)))
      when(mockGatekeeperService.fetchAppWithHistory(appId)).thenReturn(successful(expected))

      val result = await(underTest.fetchAppById(appId)(request))

      status(result) shouldBe 200
      jsonBodyOf(result) shouldBe Json.toJson(expected)
    }

    "return 404 if the application doesn't exist" in new Setup {
      when(mockGatekeeperService.fetchAppWithHistory(appId))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = await(underTest.fetchAppById(appId)(request))

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }
  }

  "approveUplift" should {
    val applicationId = UUID.randomUUID()
    val gatekeeperUserId = "big.boss.gatekeeper"
    val approveUpliftRequest = ApproveUpliftRequest(gatekeeperUserId)

    "return unauthorised when the user is not authorised" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(false))

      val result = await(underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)).withHeaders(authTokenHeader)))

      verifyZeroInteractions(mockGatekeeperService)
      verifyUnauthorized(result)
    }

    "successfully approve uplift when user is authorised" in new Setup {
      val hcArgCaptor = ArgumentCaptor.forClass(classOf[HeaderCarrier])
      when(mockAuthConnector.authorized(any[AuthRole])(hcArgCaptor.capture())).thenReturn(successful(true))

      when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId)).thenReturn(UpliftApproved)

      val result = await(underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)).withHeaders(authTokenHeader)))

      hcArgCaptor.getValue.authorization.get.value shouldBe "authorizationToken"
      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      withSuppressedLoggingFrom(Logger, "application doesn't exist") { suppressedLogs =>
        when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

        when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
          .thenReturn(failed(new NotFoundException("application doesn't exist")))

        val result = await(underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest))))

        verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
      }
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

      when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
        .thenReturn(failed(new InvalidStateTransition(TESTING, PENDING_REQUESTER_VERIFICATION, PENDING_GATEKEEPER_APPROVAL)))

      val result = await(underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest))))

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      withSuppressedLoggingFrom(Logger, "expected test failure") { suppressedLogs =>
        when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

        when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
          .thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = await(underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest))))

        verifyErrorResult(result, 500, ErrorCode.UNKNOWN_ERROR)
      }
    }
  }

  "reject Uplift" should {
    val applicationId = UUID.randomUUID()
    val gatekeeperUserId = "big.boss.gatekeeper"
    val rejectUpliftRequest = RejectUpliftRequest(gatekeeperUserId, "Test error")
    val testReq = request.withBody(Json.toJson(rejectUpliftRequest)).withHeaders(authTokenHeader)
    "return unauthorised when the user is not authorised" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(false))

      val result = await(underTest.rejectUplift(applicationId)(testReq))

      verifyZeroInteractions(mockGatekeeperService)
      verifyUnauthorized(result)
    }

    "successfully reject uplift when user is authorised" in new Setup {
      val hcArgCaptor = ArgumentCaptor.forClass(classOf[HeaderCarrier])
      when(mockAuthConnector.authorized(any[AuthRole])(hcArgCaptor.capture())).thenReturn(successful(true))

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest)).thenReturn(UpliftRejected)

      val result = await(underTest.rejectUplift(applicationId)(testReq))

      hcArgCaptor.getValue.authorization.get.value shouldBe "authorizationToken"
      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = await(underTest.rejectUplift(applicationId)(testReq))

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
        .thenReturn(failed(new InvalidStateTransition(PENDING_REQUESTER_VERIFICATION, TESTING, PENDING_GATEKEEPER_APPROVAL)))

      val result = await(underTest.rejectUplift(applicationId)(testReq))

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      withSuppressedLoggingFrom(Logger, "Expected test failure") { suppressedLogs =>
        when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

        when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
          .thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = await(underTest.rejectUplift(applicationId)(testReq))

        verifyErrorResult(result, 500, ErrorCode.UNKNOWN_ERROR)
      }
    }
  }

  "resendVerification" should {
    val applicationId = UUID.randomUUID()
    val gatekeeperUserId = "big.boss.gatekeeper"
    val resendVerificationRequest = ResendVerificationRequest(gatekeeperUserId)

    "return unauthorised when the user is not authorised" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(false))

      val result = await(underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)).withHeaders(authTokenHeader)))

      verifyZeroInteractions(mockGatekeeperService)
      verifyUnauthorized(result)
    }

    "successfully resend verification when user is authorised" in new Setup {
      val hcArgCaptor = ArgumentCaptor.forClass(classOf[HeaderCarrier])
      when(mockAuthConnector.authorized(any[AuthRole])(hcArgCaptor.capture())).thenReturn(successful(true))

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId)).thenReturn(VerificationResent)

      val result = await(underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)).withHeaders(authTokenHeader)))

      hcArgCaptor.getValue.authorization.get.value shouldBe "authorizationToken"
      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = await(underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest))))

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_REQUESTER_VERIFICATION state" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new InvalidStateTransition(PENDING_REQUESTER_VERIFICATION, PENDING_REQUESTER_VERIFICATION, PENDING_REQUESTER_VERIFICATION)))

      val result = await(underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest))))

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = await(underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest))))

      verifyErrorResult(result, 500, ErrorCode.UNKNOWN_ERROR)
    }
  }

  "deleteApplication" should {
    val applicationId = UUID.randomUUID()
    val gatekeeperUserId = "big.boss.gatekeeper"
    val requestedByEmailAddress = "admin@example.com"
    val deleteRequest = DeleteApplicationRequest(gatekeeperUserId, requestedByEmailAddress)

    "succeed with a 204 (no content) when the application is successfully deleted" in new Setup {
      when(mockGatekeeperService.deleteApplication(any(), any())(any[HeaderCarrier]())).thenReturn(successful(Deleted))

      val result = await(underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest))))

      status(result) shouldBe SC_NO_CONTENT
      verify(mockGatekeeperService).deleteApplication(applicationId, deleteRequest)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockGatekeeperService.deleteApplication(any(), any())(any[HeaderCarrier]())).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = await(underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest))))

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
      verify(mockGatekeeperService).deleteApplication(applicationId, deleteRequest)
    }

  }

  "blockApplication" should {

    val applicationId: UUID = UUID.randomUUID()

    "block the application" in new Setup {

      when(mockGatekeeperService.blockApplication(any()) (any[HeaderCarrier]())).thenReturn(successful(Blocked))

      val result = await(underTest.blockApplication(applicationId)(request))

      status(result) shouldBe SC_OK
      verify(mockGatekeeperService).blockApplication(applicationId)
    }
  }

  "unblockApplication" should {

    val applicationId: UUID = UUID.randomUUID()

    "unblock the application" in new Setup {

      when(mockGatekeeperService.unblockApplication(any()) (any[HeaderCarrier]())).thenReturn(successful(Unblocked))

      val result = await(underTest.unblockApplication(applicationId)(request))

      status(result) shouldBe SC_OK
      verify(mockGatekeeperService).unblockApplication(applicationId)
    }
  }

  private def aHistory(appId: UUID, state: State = PENDING_GATEKEEPER_APPROVAL) = {
    StateHistoryResponse(appId, state, Actor("anEmail", COLLABORATOR), None, DateTimeUtils.now)
  }

  private def anAppResult(id: UUID = UUID.randomUUID(),
                          submittedOn: DateTime = DateTimeUtils.now,
                          state: ApplicationState = testingState()) = {
    ApplicationWithUpliftRequest(id, "app 1", submittedOn, state.name)
  }

  private def verifyErrorResult(result: Result, statusCode: Int, errorCode: ErrorCode): Unit = {
    status(result) shouldBe statusCode
    (jsonBodyOf(result) \ "code").as[String] shouldBe errorCode.toString
  }

  private def anAppResponse(id: UUID = UUID.randomUUID()) = {
    new ApplicationResponse(id, "clientId", "My Application", "PRODUCTION", None, Set.empty, DateTimeUtils.now)
  }
}