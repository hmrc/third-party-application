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
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

import cats.data.OptionT
import cats.implicits._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.libs.json.Json
import play.api.mvc.{AnyContentAsEmpty, AnyContentAsJson, RequestHeader, Result}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{UserId, _}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationState, GrantLength, InvalidStateTransition, State}
import uk.gov.hmrc.apiplatform.modules.gkauth.services.{LdapGatekeeperRoleAuthorisationServiceMockModule, StrideGatekeeperRoleAuthorisationServiceMockModule}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.services.TermsOfUseInvitationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationDataServiceMockModule, ApplicationServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.EMAIL_SENT
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.models.{TermsOfUseInvitationResponse, _}
import uk.gov.hmrc.thirdpartyapplication.services.GatekeeperService
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

class GatekeeperControllerSpec extends ControllerSpec with ApplicationStateUtil with ApplicationLogger
    with ControllerTestData with ApplicationTestData with FixedClock {

  import play.api.test.Helpers._

  val authTokenHeader                                            = "authorization" -> "authorizationToken"
  implicit lazy val materializer: Materializer                   = NoMaterializer
  implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()

  trait Setup
      extends StrideGatekeeperRoleAuthorisationServiceMockModule
      with LdapGatekeeperRoleAuthorisationServiceMockModule
      with ApplicationServiceMockModule
      with TermsOfUseInvitationServiceMockModule
      with ApplicationDataServiceMockModule
      with SubmissionsServiceMockModule
      with SubmissionsTestData {
    val mockGatekeeperService           = mock[GatekeeperService]
    implicit val headers: HeaderCarrier = HeaderCarrier()

    val nowInstant = instant
    val invite     = TermsOfUseInvitation(applicationId, nowInstant, nowInstant, nowInstant.plus(21, DAYS), None, EMAIL_SENT)

    lazy val underTest =
      new GatekeeperController(
        ApplicationServiceMock.aMock,
        LdapGatekeeperRoleAuthorisationServiceMock.aMock,
        StrideGatekeeperRoleAuthorisationServiceMock.aMock,
        mockGatekeeperService,
        TermsOfUseInvitationServiceMock.aMock,
        ApplicationDataServiceMock.aMock,
        SubmissionsServiceMock.aMock,
        Helpers.stubControllerComponents()
      ) {
        override implicit def hc(implicit request: RequestHeader): HeaderCarrier = headers
      }
  }

  trait PrivilegedAndRopcSetup extends Setup {

    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = true, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

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

  def verifyForbidden(result: Future[Result]): Unit = {
    status(result) shouldBe 403
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> ErrorCode.FORBIDDEN.toString,
      "message" -> "Insufficient enrolments"
    )
  }

  "Fetch apps" should {
    "fails with unauthorised when the user is not authorised" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      val result = underTest.fetchAppsForGatekeeper(request)

      status(result) shouldBe UNAUTHORIZED

      verifyZeroInteractions(mockGatekeeperService)
    }

    "return apps for stride role" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      val expected = List(anAppResult(), anAppResult(state = productionState("user1")))
      when(mockGatekeeperService.fetchNonTestingAppsWithSubmittedDate()).thenReturn(successful(expected))

      val result = underTest.fetchAppsForGatekeeper(request)

      contentAsJson(result) shouldBe Json.toJson(expected)
    }

    "return apps for ldap role" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      val expected = List(anAppResult(), anAppResult(state = productionState("user1")))
      when(mockGatekeeperService.fetchNonTestingAppsWithSubmittedDate()).thenReturn(successful(expected))

      val result = underTest.fetchAppsForGatekeeper(request)

      contentAsJson(result) shouldBe Json.toJson(expected)
    }
  }

  "Fetch app by id" should {
    val appId = ApplicationId.random

    "fails with unauthorised when the user is not authorised" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      val result = underTest.fetchAppById(appId)(request)

      status(result) shouldBe UNAUTHORIZED

      verifyZeroInteractions(mockGatekeeperService)
    }

    "return app with history for LDAP user" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised
      val expected = ApplicationWithHistoryResponse(anAppResponse(appId), List(aHistory(appId), aHistory(appId, State.PRODUCTION)))

      when(mockGatekeeperService.fetchAppWithHistory(appId)).thenReturn(successful(expected))

      val result = underTest.fetchAppById(appId)(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expected)
    }

    "return app with history for Gatekeeper user" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      val expected = ApplicationWithHistoryResponse(anAppResponse(appId), List(aHistory(appId), aHistory(appId, State.PRODUCTION)))

      when(mockGatekeeperService.fetchAppWithHistory(appId)).thenReturn(successful(expected))

      val result = underTest.fetchAppById(appId)(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expected)
    }

    "return 404 if the application doesn't exist" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.fetchAppWithHistory(appId))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.fetchAppById(appId)(request)

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }
  }

  "fetchAppStateHistoryById" should {
    val appId = ApplicationId.random

    "return app with history for Stride GK User" in new Setup {
      val expectedStateHistories = List(aHistory(appId), aHistory(appId, State.PRODUCTION))

      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.fetchAppStateHistoryById(appId)).thenReturn(successful(expectedStateHistories))

      val result = underTest.fetchAppStateHistoryById(appId)(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expectedStateHistories)
    }

    "return app with history for LDAP GK User" in new Setup {
      val expectedStateHistories = List(aHistory(appId), aHistory(appId, State.PRODUCTION))

      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.fetchAppStateHistoryById(appId)).thenReturn(successful(expectedStateHistories))

      val result = underTest.fetchAppStateHistoryById(appId)(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expectedStateHistories)
    }
  }

  "fetchAppStateHistories" should {
    val expectedAppStateHistories = List(
      ApplicationStateHistoryResponse(
        ApplicationId.random,
        "app 1",
        1,
        List(
          ApplicationStateHistoryResponse.Item(State.TESTING, Instant.parse("2022-07-01T12:00:00Z")),
          ApplicationStateHistoryResponse.Item(State.PENDING_GATEKEEPER_APPROVAL, Instant.parse("2022-07-01T13:00:00Z")),
          ApplicationStateHistoryResponse.Item(State.PRODUCTION, Instant.parse("2022-07-01T14:00:00Z"))
        )
      ),
      ApplicationStateHistoryResponse(ApplicationId.random, "app 2", 2, List())
    )

    "return app histories for Stride GK User" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.fetchAppStateHistories()).thenReturn(successful(expectedAppStateHistories))

      val result = underTest.fetchAppStateHistories()(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expectedAppStateHistories)
    }

    "return app histories for LDAP GK User" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.fetchAppStateHistories()).thenReturn(successful(expectedAppStateHistories))

      val result = underTest.fetchAppStateHistories()(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expectedAppStateHistories)
    }
  }

  "fetchAllAppsWithSubscriptions" should {
    val expected = List(
      ApplicationWithSubscriptionsResponse(ApplicationId.random, "Application Name", None, Set())
    )

    "return app with subs for Stride GK User" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.fetchAllWithSubscriptions()).thenReturn(successful(expected))

      val result = underTest.fetchAllAppsWithSubscriptions()(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expected)
    }

    "return app with subs for LDAP GK User" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.fetchAllWithSubscriptions()).thenReturn(successful(expected))

      val result = underTest.fetchAllAppsWithSubscriptions()(request)

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(expected)
    }
  }
  "fetchAllForCollaborator" should {
    val userId                                                   = UserId.random
    val standardApplicationResponse: ExtendedApplicationResponse = aNewExtendedApplicationResponse(access = Access.Standard())

    "succeed with a 200 when applications are found for the collaborator by user id" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(userId, true))
        .thenReturn(successful(List(standardApplicationResponse)))

      status(underTest.fetchAllForCollaborator(userId)(request)) shouldBe OK
    }

    "succeed with a 200 when no applications are found for the collaborator by user id" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(userId, true)).thenReturn(successful(Nil))

      val result = underTest.fetchAllForCollaborator(userId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(userId, true)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchAllForCollaborator(userId)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  "approveUplift" should {
    val gatekeeperUserId     = "big.boss.gatekeeper"
    val approveUpliftRequest = ApproveUpliftRequest(gatekeeperUserId)

    "fails with unauthorised when the user is not authorised" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      val result = (underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)).withHeaders(authTokenHeader)))
      status(result) shouldBe UNAUTHORIZED

      verifyZeroInteractions(mockGatekeeperService)
    }

    "successfully approve uplift when user is authorised" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId)).thenReturn(successful(UpliftApproved))

      val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)).withHeaders(authTokenHeader))

      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      withSuppressedLoggingFrom(logger, "application doesn't exist") { suppressedLogs =>
        StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

        when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
          .thenReturn(failed(new NotFoundException("application doesn't exist")))

        val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)))

        verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
      }
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
        .thenReturn(failed(new InvalidStateTransition(State.TESTING, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_GATEKEEPER_APPROVAL)))

      val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)))

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      withSuppressedLoggingFrom(logger, "expected test failure") { suppressedLogs =>
        StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

        when(mockGatekeeperService.approveUplift(applicationId, gatekeeperUserId))
          .thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.approveUplift(applicationId)(request.withBody(Json.toJson(approveUpliftRequest)))

        verifyErrorResult(result, 500, ErrorCode.UNKNOWN_ERROR)
      }
    }
  }

  "reject Uplift" should {
    val gatekeeperUserId    = "big.boss.gatekeeper"
    val rejectUpliftRequest = RejectUpliftRequest(gatekeeperUserId, "Test error")
    val testReq             = request.withBody(Json.toJson(rejectUpliftRequest)).withHeaders(authTokenHeader)

    "fails with unauthorised when the user is not authorised" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      val result = underTest.rejectUplift(applicationId)(testReq)
      status(result) shouldBe UNAUTHORIZED

      verifyZeroInteractions(mockGatekeeperService)
    }

    "successfully reject uplift when user is authorised" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest)).thenReturn(successful(UpliftRejected))

      val result = underTest.rejectUplift(applicationId)(testReq)

      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.rejectUplift(applicationId)(testReq)

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_GATEKEEPER_APPROVAL state" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
        .thenReturn(failed(new InvalidStateTransition(State.PENDING_REQUESTER_VERIFICATION, State.TESTING, State.PENDING_GATEKEEPER_APPROVAL)))

      val result = underTest.rejectUplift(applicationId)(testReq)

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      withSuppressedLoggingFrom(logger, "Expected test failure") { suppressedLogs =>
        StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

        when(mockGatekeeperService.rejectUplift(applicationId, rejectUpliftRequest))
          .thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.rejectUplift(applicationId)(testReq)

        verifyErrorResult(result, 500, ErrorCode.UNKNOWN_ERROR)
      }
    }
  }

  "resendVerification" should {
    val gatekeeperUserId          = "big.boss.gatekeeper"
    val resendVerificationRequest = ResendVerificationRequest(gatekeeperUserId)

    "fails with unauthorised when the user is not authorised" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)).withHeaders(authTokenHeader))
      status(result) shouldBe UNAUTHORIZED

      verifyZeroInteractions(mockGatekeeperService)
    }

    "successfully resend verification when user is authorised" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId)).thenReturn(successful(VerificationResent))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)).withHeaders(authTokenHeader))

      status(result) shouldBe 204
    }

    "return 404 if the application doesn't exist" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)))

      verifyErrorResult(result, 404, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with 412 (Precondition Failed) when the application is not in the PENDING_REQUESTER_VERIFICATION state" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new InvalidStateTransition(State.PENDING_REQUESTER_VERIFICATION, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_REQUESTER_VERIFICATION)))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)))

      verifyErrorResult(result, 412, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.resendVerification(applicationId, gatekeeperUserId))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.resendVerification(applicationId)(request.withBody(Json.toJson(resendVerificationRequest)))

      verifyErrorResult(result, INTERNAL_SERVER_ERROR, ErrorCode.UNKNOWN_ERROR)
    }
  }

  "blockApplication" should {
    "block the application" in new Setup {

      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.blockApplication(*[ApplicationId])).thenReturn(successful(Blocked))

      val result = underTest.blockApplication(applicationId)(request)

      status(result) shouldBe OK
      verify(mockGatekeeperService).blockApplication(applicationId)
    }
  }

  "unblockApplication" should {
    "unblock the application" in new Setup {

      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.unblockApplication(*[ApplicationId])).thenReturn(successful(Unblocked))

      val result = underTest.unblockApplication(applicationId)(request)

      status(result) shouldBe OK
      verify(mockGatekeeperService).unblockApplication(applicationId)
    }
  }

  "strideUserDeleteApplication" should {
    val gatekeeperUserId        = "big.boss.gatekeeper"
    val requestedByEmailAddress = "admin@example.com".toLaxEmail
    val deleteRequest           = DeleteApplicationRequest(gatekeeperUserId, requestedByEmailAddress)

    "succeed with a 204 (no content) when the application is successfully deleted" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.deleteApplication(*[ApplicationId], *)(*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(applicationId).apply(request
        .withBody(AnyContentAsJson(Json.toJson(deleteRequest))))

      status(result) shouldBe NO_CONTENT
      verify(mockGatekeeperService).deleteApplication(eqTo(applicationId), eqTo(deleteRequest))(*)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(mockGatekeeperService.deleteApplication(*[ApplicationId], *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.deleteApplication(applicationId).apply(request
        .withBody(AnyContentAsJson(Json.toJson(deleteRequest))))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      verify(mockGatekeeperService).deleteApplication(eqTo(applicationId), eqTo(deleteRequest))(*)
    }
  }

  "create terms of use invitation" should {
    "return NOT_FOUND when an application for the given id is not found" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      ApplicationDataServiceMock.FetchApp.thenReturnNone

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe NOT_FOUND
    }

    "return CREATED when an application exists with no submission and a terms of use invitation is created" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationStateExamples.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      TermsOfUseInvitationServiceMock.FetchInvitation.thenReturnNone
      TermsOfUseInvitationServiceMock.CreateInvitation.thenReturnSuccess(invite)

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe CREATED
    }

    "return CONFLICT when a terms of use invitation already exists for the application" in new Setup {
      val dueDateInstant = nowInstant.plus(60, DAYS)

      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationStateExamples.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      val response = TermsOfUseInvitationResponse(
        applicationId,
        nowInstant,
        nowInstant,
        dueDateInstant,
        None,
        EMAIL_SENT
      )

      TermsOfUseInvitationServiceMock.FetchInvitation.thenReturn(response)

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe CONFLICT
    }

    "return CONFLICT when an application exists with a submission" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationStateExamples.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturn(aSubmission.hasCompletelyAnswered)

      TermsOfUseInvitationServiceMock.FetchInvitation.thenReturnNone
      TermsOfUseInvitationServiceMock.CreateInvitation.thenReturnSuccess(invite)

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe CONFLICT
    }

    "return INTERNAL_SERVER_ERROR when a terms of use invitation is NOT created" in new Setup {
      LdapGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      ApplicationDataServiceMock.FetchApp.thenReturn(anApplicationData(applicationId, state = ApplicationStateExamples.production("", "")))

      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      TermsOfUseInvitationServiceMock.FetchInvitation.thenReturnNone
      TermsOfUseInvitationServiceMock.CreateInvitation.thenFail()

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  private def aHistory(appId: ApplicationId, state: State = State.PENDING_GATEKEEPER_APPROVAL) = {
    StateHistoryResponse(appId, state, Actors.AppCollaborator("anEmail".toLaxEmail), None, instant)
  }

  private def anAppResult(id: ApplicationId = ApplicationId.random, submittedOn: Instant = instant, state: ApplicationState = testingState()) = {
    ApplicationWithUpliftRequest(id, "app 1", submittedOn, state.name)
  }

  private def anAppResponse(appId: ApplicationId) = {
    val grantLength = GrantLength.EIGHTEEN_MONTHS
    new Application(appId, ClientId("clientId"), "gatewayId", "My Application", "PRODUCTION", None, Set.empty, instant, Some(instant), grantLength)
  }
}
