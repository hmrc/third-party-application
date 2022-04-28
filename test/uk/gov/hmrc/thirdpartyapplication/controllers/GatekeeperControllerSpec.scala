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

import akka.stream.Materializer
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, GatekeeperService}
import uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import cats.implicits._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import cats.data.OptionT
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.UserId
import akka.stream.testkit.NoMaterializer
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

import java.time.LocalDateTime

class GatekeeperControllerSpec extends ControllerSpec with ApplicationStateUtil with FixedClock{

  import play.api.test.Helpers._

  val authTokenHeader = "authorization" -> "authorizationToken"
  implicit lazy val materializer: Materializer = NoMaterializer
  implicit lazy val request = FakeRequest()

  private val standardAccess = Standard(List("http://example.com/redirect"), Some("http://example.com/terms"), Some("http://example.com/privacy"))
  private val privilegedAccess = Privileged(scopes = Set("scope1"))
  private val ropcAccess = Ropc()

  val collaborators: Set[Collaborator] = Set(
    Collaborator("admin@example.com", ADMINISTRATOR, UserId.random),
    Collaborator("dev@example.com", DEVELOPER, UserId.random))

  trait Setup extends ApplicationLogger {
    val mockGatekeeperService = mock[GatekeeperService]
    val mockAuthConnector = mock[AuthConnector]
    val mockApplicationService = mock[ApplicationService]
    val mockSubscriptionService = mock[SubscriptionService]
    implicit val headers = HeaderCarrier()

    val mockAuthConfig = mock[AuthConnector.Config](withSettings.lenient())
    when(mockAuthConfig.enabled).thenReturn(true)
    when(mockAuthConfig.userRole).thenReturn("USER")
    when(mockAuthConfig.superUserRole).thenReturn("SUPER")
    when(mockAuthConfig.adminRole).thenReturn("ADMIN")

    val underTest = new GatekeeperController(mockAuthConnector, mockApplicationService, mockGatekeeperService, mockSubscriptionService, mockAuthConfig, Helpers.stubControllerComponents()) {
      override implicit def hc(implicit request: RequestHeader): HeaderCarrier = headers
    }
  }

  trait PrivilegedAndRopcSetup extends Setup {
    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      givenUserIsAuthenticated(underTest)

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = true, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      givenUserIsNotAuthenticated(underTest)

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = false, testBlock)
    }

    private def testWithPrivilegedAndRopc(applicationId: ApplicationId, gatekeeperLoggedIn: Boolean, testBlock: => Unit): Unit = {
      when(underTest.applicationService.fetch(applicationId))
        .thenReturn(
          OptionT.pure[Future](aNewApplicationResponse(privilegedAccess)),
          OptionT.pure[Future](aNewApplicationResponse(ropcAccess))
        )
      testBlock
      testBlock
    }
  }

  private def aNewApplicationResponse(access: Access = standardAccess, environment: Environment = Environment.PRODUCTION) = {
    val grantLengthInDays = 547

    new ApplicationResponse(
      ApplicationId.random,
      ClientId("clientId"),
      "gatewayId",
      "My Application",
      environment.toString,
      Some("Description"),
      collaborators,
      LocalDateTime.now,
      Some(LocalDateTime.now),
      grantLengthInDays,
      None,
      standardAccess.redirectUris,
      standardAccess.termsAndConditionsUrl,
      standardAccess.privacyPolicyUrl,
      access
    )
  }

  def verifyForbidden(result: Future[Result]): Unit = {
    status(result) shouldBe 403
    contentAsJson(result) shouldBe Json.obj(
      "code" -> ErrorCode.FORBIDDEN.toString, "message" -> "Insufficient enrolments"
    )
  }

  private def anAPIJson() = {
    """{ "context" : "some-context", "version" : "1.0" }"""
  }

  "createSubscriptionForApplication" should {
    val applicationId = ApplicationId.random
    val body = anAPIJson()

    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "succeed with a 204 (no content) when a subscription is successfully added to a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(applicationId), *)(*))
        .thenReturn(successful(HasSucceeded))

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe NO_CONTENT
    }

    "succeed with a 204 (no content) when a subscription is successfully added to a PRIVILEGED or ROPC application and the gatekeeper is logged in" in
      new PrivilegedAndRopcSetup {

        givenUserIsAuthenticated(underTest)

        testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
          when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(applicationId), *)(*))
            .thenReturn(successful(HasSucceeded))

          status(underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))) shouldBe NO_CONTENT
        })
      }

    "fail with 401 (Unauthorized) when adding a subscription to a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in
      new PrivilegedAndRopcSetup {

        testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
          assertThrows[SessionRecordNotFound](await(underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))))
        })
      }

    "fail with a 422 (unprocessable entity) when unexpected json is provided" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val body = """{ "json": "invalid" }"""

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(applicationId), *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "Fetch apps" should {
    "throws SessionRecordNotFound when the user is not authorised" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.fetchAppsForGatekeeper(request)))

      verifyZeroInteractions(mockGatekeeperService)
    }

    "return apps" in new Setup {
      val expected = List(anAppResult(), anAppResult(state = productionState("user1")))
      when(mockGatekeeperService.fetchNonTestingAppsWithSubmittedDate()).thenReturn(successful(expected))

      givenUserIsAuthenticated(underTest)

      val result = underTest.fetchAppsForGatekeeper(request)

      contentAsJson(result) shouldBe Json.toJson(expected)
    }
  }

  "Fetch app by id" should {
    val appId = ApplicationId.random

    "throws SessionRecordNotFound when the user is not authorised" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.fetchAppById(appId)(request)))

      verifyZeroInteractions(mockGatekeeperService)
    }

    "return app with history" in new Setup {
      val expected = ApplicationWithHistory(anAppResponse(appId), List(aHistory(appId), aHistory(appId, State.PRODUCTION)))

      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.fetchAppWithHistory(appId)).thenReturn(successful(expected))

      val result = underTest.fetchAppById(appId)(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expected)
    }

    "return 404 if the application doesn't exist" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.fetchAppWithHistory(appId))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.fetchAppById(appId)(request)

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }
  }

  "fetchAppStateHistoryById" should {
    val appId = ApplicationId.random

    "return app with history" in new Setup {
      val expectedStateHistories = List(aHistory(appId), aHistory(appId, State.PRODUCTION))

      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.fetchAppStateHistoryById(appId)).thenReturn(successful(expectedStateHistories))

      val result = underTest.fetchAppStateHistoryById(appId)(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expectedStateHistories)
    }
  }

  "approveUplift" should {
    val applicationId = ApplicationId.random
    val gatekeeperUserId = "big.boss.gatekeeper"
    val approveUpliftRequest = ApproveUpliftRequest(gatekeeperUserId)

    "throws SessionRecordNotFound when the user is not authorised" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)).withHeaders(authTokenHeader))))

      verifyZeroInteractions(mockGatekeeperService)
    }

    "successfully approve uplift when user is authorised" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId)).thenReturn(successful(UpliftApproved))

      val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)).withHeaders(authTokenHeader))

      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      withSuppressedLoggingFrom(logger, "application doesn't exist") { suppressedLogs =>
        givenUserIsAuthenticated(underTest)

        when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
          .thenReturn(failed(new NotFoundException("application doesn't exist")))

        val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)))

        verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
      }
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
        .thenReturn(failed(new InvalidStateTransition(State.TESTING, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_GATEKEEPER_APPROVAL)))

      val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)))

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      withSuppressedLoggingFrom(logger, "expected test failure") { suppressedLogs =>
        givenUserIsAuthenticated(underTest)

        when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
          .thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)))

        verifyErrorResult(result, 500, ErrorCode.UNKNOWN_ERROR)
      }
    }
  }

  "reject Uplift" should {
    val applicationId = ApplicationId.random
    val gatekeeperUserId = "big.boss.gatekeeper"
    val rejectUpliftRequest = RejectUpliftRequest(gatekeeperUserId, "Test error")
    val testReq = request.withBody(Json.toJson(rejectUpliftRequest)).withHeaders(authTokenHeader)
    "throws SessionRecordNotFound when the user is not authorised" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.rejectUplift(applicationId)(testReq)))

      verifyZeroInteractions(mockGatekeeperService)
    }

    "successfully reject uplift when user is authorised" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest)).thenReturn(successful(UpliftRejected))

      val result = underTest.rejectUplift(applicationId)(testReq)

      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.rejectUplift(applicationId)(testReq)

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
        .thenReturn(failed(new InvalidStateTransition(State.PENDING_REQUESTER_VERIFICATION, State.TESTING, State.PENDING_GATEKEEPER_APPROVAL)))

      val result = underTest.rejectUplift(applicationId)(testReq)

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      withSuppressedLoggingFrom(logger, "Expected test failure") { suppressedLogs =>
        givenUserIsAuthenticated(underTest)

        when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
          .thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.rejectUplift(applicationId)(testReq)

        verifyErrorResult(result, 500, ErrorCode.UNKNOWN_ERROR)
      }
    }
  }

  "resendVerification" should {
    val applicationId = ApplicationId.random
    val gatekeeperUserId = "big.boss.gatekeeper"
    val resendVerificationRequest = ResendVerificationRequest(gatekeeperUserId)

    "throws SessionRecordNotFound when the user is not authorised" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)).withHeaders(authTokenHeader))))

      verifyZeroInteractions(mockGatekeeperService)
    }

    "successfully resend verification when user is authorised" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId)).thenReturn(successful(VerificationResent))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)).withHeaders(authTokenHeader))

      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)))

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_REQUESTER_VERIFICATION state" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new InvalidStateTransition(State.PENDING_REQUESTER_VERIFICATION, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_REQUESTER_VERIFICATION)))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)))

      verifyErrorResult(result,  412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)))

      verifyErrorResult(result, INTERNAL_SERVER_ERROR, ErrorCode.UNKNOWN_ERROR)
    }
  }

  "blockApplication" should {
    val applicationId: ApplicationId = ApplicationId.random

    "block the application" in new Setup {

      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.blockApplication(*[ApplicationId])).thenReturn(successful(Blocked))

      val result = underTest.blockApplication(applicationId)(request)

      status(result) shouldBe OK
      verify(mockGatekeeperService).blockApplication(applicationId)
    }
  }

  "unblockApplication" should {
    val applicationId: ApplicationId = ApplicationId.random

    "unblock the application" in new Setup {

      givenUserIsAuthenticated(underTest)

      when(mockGatekeeperService.unblockApplication(*[ApplicationId])).thenReturn(successful(Unblocked))

      val result = underTest.unblockApplication(applicationId)(request)

      status(result) shouldBe OK
      verify(mockGatekeeperService).unblockApplication(applicationId)
    }
  }

  private def aHistory(appId: ApplicationId, state: State = State.PENDING_GATEKEEPER_APPROVAL) = {
    StateHistoryResponse(appId, state, Actor("anEmail", COLLABORATOR), None, LocalDateTime.now)
  }

  private def anAppResult(id: ApplicationId = ApplicationId.random,
                          submittedOn: LocalDateTime = LocalDateTime.now,
                          state: ApplicationState = testingState()) = {
    ApplicationWithUpliftRequest(id, "app 1", submittedOn, state.name)
  }

  private def anAppResponse(appId: ApplicationId) = {
    val grantLengthInDays = 547
    new ApplicationResponse(appId, ClientId("clientId"), "gatewayId", "My Application", "PRODUCTION", None, Set.empty, LocalDateTime.now, Some(LocalDateTime.now), grantLengthInDays)
  }
}
