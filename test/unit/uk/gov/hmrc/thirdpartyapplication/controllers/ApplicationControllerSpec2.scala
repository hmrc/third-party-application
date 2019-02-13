/*
 * Copyright 2019 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.controllers

import java.util.UUID

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.apache.http.HttpStatus._
import org.mockito.Matchers.{any, eq => mockEq}
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeRequest
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.auth.core.retrieve.Retrieval
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.controllers._
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, CredentialService, SubscriptionService}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.Future.{apply => _, _}
import scala.concurrent.{ExecutionContext, Future}

class ApplicationControllerSpec2 extends UnitSpec with ScalaFutures with MockitoSugar with WithFakeApplication with ApplicationStateUtil {

  implicit lazy val materializer = fakeApplication.materializer

  trait Setup {
    //    implicit val hc = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    implicit lazy val request = FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    private val mockCredentialService = mock[CredentialService]
    private val mockApplicationService = mock[ApplicationService]
    private val mockAuthConnector = mock[AuthConnector]
    private val mockSubscriptionService = mock[SubscriptionService]
    private val mockAuthConfig = mock[AuthConfig]

    private val applicationTtlInSecs = 1234
    private val subscriptionTtlInSecs = 4321
    private val config = ApplicationControllerConfig(applicationTtlInSecs, subscriptionTtlInSecs)

    when(mockAuthConfig.userRole).thenReturn("user-role")
    when(mockAuthConfig.superUserRole).thenReturn("super-user-role")
    when(mockAuthConfig.adminRole).thenReturn("admin-role")

    implicit val underTest = new ApplicationController(
      mockApplicationService,
      mockAuthConnector,
      mockCredentialService,
      mockSubscriptionService,
      config,
      mockAuthConfig)
  }

  private val collaborators = Set(
    Collaborator("admin@example.com", ADMINISTRATOR),
    Collaborator("dev@example.com", DEVELOPER))

  private val standardAccess = Standard(Seq("http://example.com/redirect"), Some("http://example.com/terms"), Some("http://example.com/privacy"))
  private val privilegedAccess = Privileged(scopes = Set("scope1"))
  private val ropcAccess = Ropc()

  "Create" should {
    val standardApplicationRequest = aCreateApplicationRequest(standardAccess, Environment.PRODUCTION)
    val privilegedApplicationRequest = aCreateApplicationRequest(privilegedAccess, Environment.PRODUCTION)
    val ropcApplicationRequest = aCreateApplicationRequest(ropcAccess, Environment.PRODUCTION)

    val standardApplicationResponse = CreateApplicationResponse(aNewApplicationResponse())
    val totp = TotpSecrets("pTOTP", "sTOTP")
    val privilegedApplicationResponse = CreateApplicationResponse(aNewApplicationResponse(privilegedAccess), Some(totp))
    val ropcApplicationResponse = CreateApplicationResponse(aNewApplicationResponse(ropcAccess))

    "succeed with a 201 (Created) for a valid Standard application request when service responds successfully" in new Setup {
      givenAnAuthenticatedRequest

      when(underTest.applicationService.create(mockEq(standardApplicationRequest))(any[HeaderCarrier]))
        .thenReturn(successful(standardApplicationResponse))

      private val result = await(underTest.create()(request.withBody(Json.toJson(standardApplicationRequest))))

      status(result) shouldBe SC_CREATED
      verify(underTest.applicationService).create(mockEq(standardApplicationRequest))(any[HeaderCarrier])
    }

    "succeed with a 201 (Created) for a valid Privileged application request when gatekeeper is logged in and service responds successfully" in new Setup {

      givenAnAuthenticatedRequest

      when(underTest.applicationService.create(mockEq(privilegedApplicationRequest))(any[HeaderCarrier])).thenReturn(successful(privilegedApplicationResponse))

      val result = await(underTest.create()(request.withBody(Json.toJson(privilegedApplicationRequest))))

      (jsonBodyOf(result) \ "totp").as[TotpSecrets] shouldBe totp
      status(result) shouldBe SC_CREATED
      verify(underTest.applicationService).create(mockEq(privilegedApplicationRequest))(any[HeaderCarrier])
    }

    "succeed with a 201 (Created) for a valid ROPC application request when gatekeeper is logged in and service responds successfully" in new Setup {
      givenAnAuthenticatedRequest

      when(underTest.applicationService.create(mockEq(ropcApplicationRequest))(any[HeaderCarrier])).thenReturn(successful(ropcApplicationResponse))

      val result = await(underTest.create()(request.withBody(Json.toJson(ropcApplicationRequest))))

      status(result) shouldBe SC_CREATED
      verify(underTest.applicationService).create(mockEq(ropcApplicationRequest))(any[HeaderCarrier])
    }

    "fail for a unauthenticated application request" in new Setup {
      givenAnUnauthenticatedRequest

      assertThrows[SessionRecordNotFound] {
        await(underTest.create()(request.withBody(Json.toJson(standardApplicationRequest))))
      }
    }

    // TODO - Doesn't check valid application in request or existing
  }


  def givenAnAuthenticatedRequest(implicit underTest: ApplicationController) = {
    when(underTest.authConnector.authorise(any, any[Retrieval[Any]])(any[HeaderCarrier], any[ExecutionContext]))
      .thenReturn(Future.successful())

  }

  def givenAnUnauthenticatedRequest(implicit underTest: ApplicationController) = {
    when(underTest.authConnector.authorise(any, any[Retrieval[Any]])(any[HeaderCarrier], any[ExecutionContext]))
      .thenReturn(Future.failed(new SessionRecordNotFound))

  }


  private def aNewApplicationResponse(access: Access = standardAccess)

  = {
    new ApplicationResponse(
      UUID.randomUUID(),
      "clientId",
      "My Application",
      "PRODUCTION",
      Some("Description"),
      collaborators,
      DateTimeUtils.now,
      standardAccess.redirectUris,
      standardAccess.termsAndConditionsUrl,
      standardAccess.privacyPolicyUrl,
      access)
  }

  private def aCreateApplicationRequest(access: Access, environment: Environment)

  = CreateApplicationRequest("My Application", access, Some("Description"),
    environment, Set(Collaborator("admin@example.com", ADMINISTRATOR), Collaborator("dev@example.com", ADMINISTRATOR)))
}

