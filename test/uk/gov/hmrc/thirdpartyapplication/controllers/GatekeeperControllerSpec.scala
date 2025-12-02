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
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.libs.json.Json
import play.api.mvc.{AnyContentAsEmpty, AnyContentAsJson, RequestHeader, Result}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.gkauth.services.{LdapGatekeeperRoleAuthorisationServiceMockModule, StrideGatekeeperRoleAuthorisationServiceMockModule}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.services.TermsOfUseInvitationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationDataServiceMockModule, ApplicationServiceMockModule, QueryServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.EMAIL_SENT
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.models.{DeleteApplicationRequest, _}
import uk.gov.hmrc.thirdpartyapplication.services.GatekeeperService
import uk.gov.hmrc.thirdpartyapplication.util._

class GatekeeperControllerSpec extends ControllerSpec with ApplicationLogger
    with ControllerTestData with StoredApplicationFixtures with FixedClock {

  import play.api.test.Helpers._

  val authTokenHeader                                            = "authorization" -> "authorizationToken"
  implicit lazy val materializer: Materializer                   = NoMaterializer
  implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()

  trait Setup
      extends StrideGatekeeperRoleAuthorisationServiceMockModule
      with LdapGatekeeperRoleAuthorisationServiceMockModule
      with QueryServiceMockModule
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
        QueryServiceMock.aMock,
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
          OptionT.pure[Future](privilegedApp),
          OptionT.pure[Future](ropcApp)
        )
      testBlock
      testBlock
    }
  }

  def verifyForbidden(result: Future[Result]): Unit = {
    status(result) shouldBe 403
    contentAsJson(result) shouldBe Json.obj(
      "code"    -> common.ErrorCode.FORBIDDEN.toString,
      "message" -> "Insufficient enrolments"
    )
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
}
