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

import org.mockito.Matchers._
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeRequest
import uk.gov.hmrc.thirdpartyapplication.connector.AuthConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.{OverridesRequest, _}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.services.{AccessService, ApplicationService}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

class AccessControllerSpec extends UnitSpec with MockitoSugar with WithFakeApplication {

  implicit lazy val materializer = fakeApplication.materializer
  private val overrides = Set[OverrideFlag](PersistLogin(), GrantWithoutConsent(Set("scope1", "scope2")))
  private val scopes = Set("scope")
  private val scopeRequest = ScopeRequest(scopes)
  private val overridesRequest = OverridesRequest(overrides)
  private val applicationId = UUID.randomUUID()

  private val mockApplicationService = mock[ApplicationService]
  private val mockAuthConnector = mock[AuthConnector]
  private val mockAccessService = mock[AccessService]

  implicit val fakeRequest = FakeRequest()
  implicit val headerCarrier = HeaderCarrier()

  "Access controller read scopes function" should {

    def mockAccessServiceReadScopesToReturn(eventualScopeResponse: Future[ScopeResponse]) =
      when(mockAccessService.readScopes(any[UUID])).thenReturn(eventualScopeResponse)

    "return http ok status when service read scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceReadScopesToReturn(successful(ScopeResponse(scopes)))
        status(invokeAccessControllerReadScopesWith(applicationId)) shouldBe OK
      })
    }

    "return resource as response body when service read scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceReadScopesToReturn(successful(ScopeResponse(scopes)))
        jsonBodyOf(invokeAccessControllerReadScopesWith(applicationId)) shouldBe Json.toJson(ScopeResponse(scopes))
      })
    }

    "return http internal server error status when service read scopes fails with exception" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceReadScopesToReturn(failed(new RuntimeException("testing testing 123")))
        status(invokeAccessControllerReadScopesWith(applicationId)) shouldBe INTERNAL_SERVER_ERROR
      })
    }

  }

  "Access controller update scopes function" should {

    def mockAccessServiceUpdateScopesToReturn(eventualScopeResponse: Future[ScopeResponse]) =
      when(mockAccessService.updateScopes(any[UUID], any[ScopeRequest])(any[HeaderCarrier])).thenReturn(eventualScopeResponse)

    "return http ok status when service update scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceUpdateScopesToReturn(successful(ScopeResponse(scopes)))
        status(invokeAccessControllerUpdateScopesWith(applicationId, scopeRequest)) shouldBe OK
      })
    }

    "return resource as response body when service update scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceUpdateScopesToReturn(successful(ScopeResponse(scopes)))
        jsonBodyOf(invokeAccessControllerUpdateScopesWith(applicationId, scopeRequest)) shouldBe Json.toJson(ScopeResponse(scopes))
      })
    }

    "return http internal server error status when service update scopes fails" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceUpdateScopesToReturn(failed(new RuntimeException("testing testing 123")))
        status(invokeAccessControllerUpdateScopesWith(applicationId, scopeRequest)) shouldBe INTERNAL_SERVER_ERROR
      })
    }
  }

  "Access controller overrides crud functions" should {

    "return http unauthorized status when application id refers to a non-standard application" in new PrivilegedAndRopcFixture {
      status(invokeAccessControllerReadOverridesWith(applicationId)) shouldBe UNAUTHORIZED
      status(invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)) shouldBe UNAUTHORIZED
    }

  }

  "Access controller read overrides function" should {

    def mockAccessServiceReadOverridesToReturn(eventualOverridesResponse: Future[OverridesResponse]) =
      when(mockAccessService.readOverrides(any[UUID])).thenReturn(eventualOverridesResponse)

    "return http ok status when service read overrides succeeds" in new StandardFixture {
      mockAccessServiceReadOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerReadOverridesWith(applicationId)
      status(result) shouldBe OK
    }

    "return resource as response body when service read overrides succeeds" in new StandardFixture {
      mockAccessServiceReadOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerReadOverridesWith(applicationId)
      jsonBodyOf(result) shouldBe Json.toJson(OverridesResponse(overrides))
    }

    "return http internal server error status when service read overrides fails with exception" in new StandardFixture {
      mockAccessServiceReadOverridesToReturn(failed(new RuntimeException("testing testing 123")))
      val result = invokeAccessControllerReadOverridesWith(applicationId)
      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "Access controller update overrides function" should {

    def mockAccessServiceUpdateOverridesToReturn(eventualOverridesResponse: Future[OverridesResponse]) =
      when(mockAccessService.updateOverrides(any[UUID], any[OverridesRequest])(any[HeaderCarrier])).thenReturn(eventualOverridesResponse)

    "return http ok status when service update overrides succeeds" in new StandardFixture {
      mockAccessServiceUpdateOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)
      status(result) shouldBe OK
    }

    "return resource as response body when service update overrides succeeds" in new StandardFixture {
      mockAccessServiceUpdateOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)
      jsonBodyOf(result) shouldBe Json.toJson(OverridesResponse(overrides))
    }

    "return http internal server error status when service update overrides fails" in new StandardFixture {
      mockAccessServiceUpdateOverridesToReturn(failed(new RuntimeException("testing testing 123")))
      val result = invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)
      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  trait Fixture {

    private[controllers] val accessController = new AccessController(mockAccessService, mockAuthConnector, mockApplicationService)

    when(mockAuthConnector.authorized(any[AuthRole])(any[HeaderCarrier])).thenReturn(successful(true))

    def invokeAccessControllerReadScopesWith(applicationId: UUID): Result =
      await(accessController.readScopes(applicationId)(fakeRequest))

    def invokeAccessControllerUpdateScopesWith(applicationId: UUID, scopeRequest: ScopeRequest): Result =
      await(accessController.updateScopes(applicationId)(fakeRequest.withBody(Json.toJson(scopeRequest))))

    def invokeAccessControllerReadOverridesWith(applicationId: UUID): Result =
      await(accessController.readOverrides(applicationId)(fakeRequest))

    def invokeAccessControllerUpdateOverridesWith(applicationId: UUID, overridesRequest: OverridesRequest): Result =
      await(accessController.updateOverrides(applicationId)(fakeRequest.withBody(Json.toJson(overridesRequest))))

  }

  trait StandardFixture extends Fixture {
    when(mockApplicationService.fetch(applicationId)).thenReturn(successful(Some(
      new ApplicationResponse(
        applicationId,
        "clientId",
        "name",
        "PRODUCTION",
        Some("description"),
        Set.empty,
        DateTimeUtils.now,
        access = Standard())))
    )
  }

  trait PrivilegedAndRopcFixture extends Fixture {

    def testWithPrivilegedAndRopc(testBlock: => Unit): Unit = {
      val applicationResponse = ApplicationResponse(applicationId, "clientId", "name", "PRODUCTION", None, Set.empty, DateTimeUtils.now)
      when(mockApplicationService.fetch(applicationId)).thenReturn(successful(Some(
        applicationResponse.copy(clientId = "privilegedClientId", name = "privilegedName", access = Privileged(scopes = Set("scope:privilegedScopeKey")))
      ))).thenReturn(successful(Some(
        applicationResponse.copy(clientId = "ropcClientId", name = "ropcName", access = Ropc(Set("scope:ropcScopeKey")))
      )))
      testBlock
      testBlock
    }
  }

}
