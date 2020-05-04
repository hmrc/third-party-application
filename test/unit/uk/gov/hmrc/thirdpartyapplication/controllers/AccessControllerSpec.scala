/*
 * Copyright 2020 HM Revenue & Customs
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

import akka.stream.Materializer
import cats.data.OptionT
import cats.implicits._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.libs.json.Json
import play.api.mvc.{AnyContentAsEmpty, ControllerComponents, Result}
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.{OverridesRequest, _}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.services.{AccessService, ApplicationService}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.DateTimeUtils
import unit.uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

class AccessControllerSpec extends ControllerSpec {
  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = fakeApplication().materializer

  private val overrides = Set[OverrideFlag](PersistLogin(), GrantWithoutConsent(Set("scope1", "scope2")))
  private val scopes = Set("scope")
  private val scopeRequest = ScopeRequest(scopes)
  private val overridesRequest = OverridesRequest(overrides)
  private val applicationId = UUID.randomUUID()

  private val mockApplicationService = mock[ApplicationService]
  private val mockAuthConnector = mock[AuthConnector]
  private val mockAccessService = mock[AccessService]
  private val mockAuthConfig = mock[AuthConfig]
  private val mockControllerComponents = mock[ControllerComponents]

  implicit private val fakeRequest: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
  implicit private val headerCarrier: HeaderCarrier = HeaderCarrier()

  "Access controller read scopes function" should {

    def mockAccessServiceReadScopesToReturn(eventualScopeResponse: Future[ScopeResponse]) =
      when(mockAccessService.readScopes(*)).thenReturn(eventualScopeResponse)

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

    def mockAccessServiceUpdateScopesToReturn(eventualScopeResponse: Future[ScopeResponse]) =
      when(mockAccessService.updateScopes(*, any[ScopeRequest])(*)).thenReturn(eventualScopeResponse)

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

    def mockAccessServiceReadOverridesToReturn(eventualOverridesResponse: Future[OverridesResponse]) =
      when(mockAccessService.readOverrides(*)).thenReturn(eventualOverridesResponse)

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

    def mockAccessServiceUpdateOverridesToReturn(eventualOverridesResponse: Future[OverridesResponse]) =
      when(mockAccessService.updateOverrides(*, any[OverridesRequest])(*)).thenReturn(eventualOverridesResponse)

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

    private[controllers] val accessController = new AccessController(mockAuthConnector, mockApplicationService, mockAuthConfig, mockAccessService, mockControllerComponents)

    givenUserIsAuthenticated(accessController)

    def invokeAccessControllerReadScopesWith(applicationId: UUID): Future[Result] =
      accessController.readScopes(applicationId)(fakeRequest)

    def invokeAccessControllerUpdateScopesWith(applicationId: UUID, scopeRequest: ScopeRequest): Future[Result] =
      accessController.updateScopes(applicationId)(fakeRequest.withBody(Json.toJson(scopeRequest)))

    def invokeAccessControllerReadOverridesWith(applicationId: UUID): Future[Result] =
      accessController.readOverrides(applicationId)(fakeRequest)

    def invokeAccessControllerUpdateOverridesWith(applicationId: UUID, overridesRequest: OverridesRequest): Future[Result] =
      accessController.updateOverrides(applicationId)(fakeRequest.withBody(Json.toJson(overridesRequest)))
  }



  trait StandardFixture extends Fixture {


    when(mockApplicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](
      ApplicationResponse(
        applicationId,
        "clientId",
        "gatewayId",
        "name",
        "PRODUCTION",
        Some("description"),
        Set.empty,
        DateTimeUtils.now,
        Some(DateTimeUtils.now),
        access = Standard())
    ))
  }

  trait PrivilegedAndRopcFixture extends Fixture {

    def testWithPrivilegedAndRopc(testBlock: => Unit): Unit = {
      val applicationResponse =
        ApplicationResponse(applicationId, "clientId", "gatewayId", "name", "PRODUCTION", None, Set.empty, DateTimeUtils.now, Some(DateTimeUtils.now))
      when(mockApplicationService.fetch(applicationId)).thenReturn(
        OptionT.pure[Future](
          applicationResponse.copy(clientId = "privilegedClientId", name = "privilegedName", access = Privileged(scopes = Set("scope:privilegedScopeKey")))
        ),
      OptionT.pure[Future](applicationResponse.copy(clientId = "ropcClientId", name = "ropcName", access = Ropc(Set("scope:ropcScopeKey"))))
      )
      testBlock
      testBlock
    }
  }

}
