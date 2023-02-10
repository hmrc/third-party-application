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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

import akka.stream.Materializer
import akka.stream.testkit.NoMaterializer
import cats.data.OptionT
import cats.implicits._

import play.api.libs.json.Json
import play.api.mvc.{AnyContentAsEmpty, Result}
import play.api.test.FakeRequest

import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.services.{AccessService, ApplicationService}
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId

class AccessControllerSpec extends ControllerSpec with StrideGatekeeperRoleAuthorisationServiceMockModule with ApplicationServiceMockModule {
  import play.api.test.Helpers._
  import play.api.test.Helpers

  implicit lazy val materializer: Materializer = NoMaterializer

  private val overrides        = Set[OverrideFlag](PersistLogin, GrantWithoutConsent(Set("scope1", "scope2")))
  private val scopes           = Set("scope")
  private val scopeRequest     = ScopeRequest(scopes)
  private val overridesRequest = OverridesRequest(overrides)
  private val applicationId    = ApplicationId.random

  implicit private val fakeRequest: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()

  val mockApplicationService   = mock[ApplicationService]
  val mockAccessService        = mock[AccessService]
  val mockControllerComponents = Helpers.stubControllerComponents()

  "Access controller read scopes function" should {

    "return http ok status when service read scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceReadScopesToReturn(successful(ScopeResponse(scopes)))
        status(invokeAccessControllerReadScopesWith(applicationId)) shouldBe OK
      })
    }

    "return resource as response body when service read scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceReadScopesToReturn(successful(ScopeResponse(scopes)))
        contentAsJson(invokeAccessControllerReadScopesWith(applicationId)) shouldBe Json.toJson(ScopeResponse(scopes))
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

    "return http ok status when service update scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceUpdateScopesToReturn(successful(ScopeResponse(scopes)))
        status(invokeAccessControllerUpdateScopesWith(applicationId, scopeRequest)) shouldBe OK
      })
    }

    "return resource as response body when service update scopes succeeds" in new PrivilegedAndRopcFixture {
      testWithPrivilegedAndRopc({
        mockAccessServiceUpdateScopesToReturn(successful(ScopeResponse(scopes)))
        contentAsJson(invokeAccessControllerUpdateScopesWith(applicationId, scopeRequest)) shouldBe Json.toJson(ScopeResponse(scopes))
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

    "return http forbidden status when application id refers to a non-standard application" in new PrivilegedAndRopcFixture {
      status(invokeAccessControllerReadOverridesWith(applicationId)) shouldBe FORBIDDEN
      status(invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)) shouldBe FORBIDDEN
    }

  }

  "Access controller read overrides function" should {

    "return http ok status when service read overrides succeeds" in new StandardFixture {
      mockAccessServiceReadOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerReadOverridesWith(applicationId)
      status(result) shouldBe OK
    }

    "return resource as response body when service read overrides succeeds" in new StandardFixture {
      mockAccessServiceReadOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerReadOverridesWith(applicationId)
      contentAsJson(result) shouldBe Json.toJson(OverridesResponse(overrides))
    }

    "return http internal server error status when service read overrides fails with exception" in new StandardFixture {
      mockAccessServiceReadOverridesToReturn(failed(new RuntimeException("testing testing 123")))
      val result = invokeAccessControllerReadOverridesWith(applicationId)
      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "Access controller update overrides function" should {

    "return http ok status when service update overrides succeeds" in new StandardFixture {
      mockAccessServiceUpdateOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)
      status(result) shouldBe OK
    }

    "return resource as response body when service update overrides succeeds" in new StandardFixture {
      mockAccessServiceUpdateOverridesToReturn(successful(OverridesResponse(overrides)))
      val result = invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)
      contentAsJson(result) shouldBe Json.toJson(OverridesResponse(overrides))
    }

    "return http internal server error status when service update overrides fails" in new StandardFixture {
      mockAccessServiceUpdateOverridesToReturn(failed(new RuntimeException("testing testing 123")))
      val result = invokeAccessControllerUpdateOverridesWith(applicationId, overridesRequest)
      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  trait Fixture {

    def mockAccessServiceReadScopesToReturn(eventualScopeResponse: Future[ScopeResponse]) =
      when(mockAccessService.readScopes(*[ApplicationId])).thenReturn(eventualScopeResponse)

    def mockAccessServiceUpdateScopesToReturn(eventualScopeResponse: Future[ScopeResponse]) =
      when(mockAccessService.updateScopes(*[ApplicationId], *)(*)).thenReturn(eventualScopeResponse)

    def mockAccessServiceReadOverridesToReturn(eventualOverridesResponse: Future[OverridesResponse]) =
      when(mockAccessService.readOverrides(*[ApplicationId])).thenReturn(eventualOverridesResponse)

    def mockAccessServiceUpdateOverridesToReturn(eventualOverridesResponse: Future[OverridesResponse]) =
      when(mockAccessService.updateOverrides(*[ApplicationId], any[OverridesRequest])(*)).thenReturn(eventualOverridesResponse)

    lazy val accessController = new AccessController(StrideGatekeeperRoleAuthorisationServiceMock.aMock, mockApplicationService, mockAccessService, mockControllerComponents)

    StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

    def invokeAccessControllerReadScopesWith(applicationId: ApplicationId): Future[Result] =
      accessController.readScopes(applicationId)(fakeRequest)

    def invokeAccessControllerUpdateScopesWith(applicationId: ApplicationId, scopeRequest: ScopeRequest): Future[Result] =
      accessController.updateScopes(applicationId)(fakeRequest.withBody(Json.toJson(scopeRequest)))

    def invokeAccessControllerReadOverridesWith(applicationId: ApplicationId): Future[Result] =
      accessController.readOverrides(applicationId)(fakeRequest)

    def invokeAccessControllerUpdateOverridesWith(applicationId: ApplicationId, overridesRequest: OverridesRequest): Future[Result] =
      accessController.updateOverrides(applicationId)(fakeRequest.withBody(Json.toJson(overridesRequest)))
  }

  trait StandardFixture extends Fixture {
    val grantLengthInDays = 547
    when(mockApplicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](
      ApplicationResponse(
        applicationId,
        ClientId("clientId"),
        "gatewayId",
        "name",
        "PRODUCTION",
        Some("description"),
        Set.empty,
        FixedClock.now,
        Some(FixedClock.now),
        grantLengthInDays,
        access = Standard()
      )
    ))
  }

  trait PrivilegedAndRopcFixture extends Fixture {
    val grantLengthInDays = 547

    def testWithPrivilegedAndRopc(testBlock: => Unit): Unit = {
      val applicationResponse =
        ApplicationResponse(applicationId, ClientId("clientId"), "gatewayId", "name", "PRODUCTION", None, Set.empty, FixedClock.now, Some(FixedClock.now), grantLengthInDays)
      when(mockApplicationService.fetch(applicationId)).thenReturn(
        OptionT.pure[Future](
          applicationResponse.copy(clientId = ClientId("privilegedClientId"), name = "privilegedName", access = Privileged(scopes = Set("scope:privilegedScopeKey")))
        ),
        OptionT.pure[Future](applicationResponse.copy(clientId = ClientId("ropcClientId"), name = "ropcName", access = Ropc(Set("scope:ropcScopeKey"))))
      )
      testBlock
      testBlock
    }
  }

}
