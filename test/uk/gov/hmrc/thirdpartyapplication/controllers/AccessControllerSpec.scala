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

import cats.data.OptionT
import cats.implicits._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.libs.json.Json
import play.api.mvc.{AnyContentAsEmpty, Result}
import play.api.test.FakeRequest

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.OverrideFlag
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services.{AccessService, ApplicationService}
import uk.gov.hmrc.thirdpartyapplication.util.CommonApplicationId

class AccessControllerSpec
    extends ControllerSpec
    with StrideGatekeeperRoleAuthorisationServiceMockModule
    with ApplicationServiceMockModule
    with ApplicationWithCollaboratorsFixtures
    with CommonApplicationId {
  import play.api.test.Helpers._
  import play.api.test.Helpers

  implicit lazy val materializer: Materializer = NoMaterializer

  private val overrides = Set[OverrideFlag](OverrideFlag.PersistLogin, OverrideFlag.GrantWithoutConsent(Set("scope1", "scope2")))
  private val scopes    = Set("scope")

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

  "Access controller overrides crud functions" should {

    "return http forbidden status when application id refers to a non-standard application" in new PrivilegedAndRopcFixture {
      status(invokeAccessControllerReadOverridesWith(applicationId)) shouldBe FORBIDDEN
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

  trait Fixture extends ApplicationWithCollaboratorsFixtures {

    def mockAccessServiceReadScopesToReturn(eventualScopeResponse: Future[ScopeResponse]) =
      when(mockAccessService.readScopes(*[ApplicationId])).thenReturn(eventualScopeResponse)

    def mockAccessServiceReadOverridesToReturn(eventualOverridesResponse: Future[OverridesResponse]) =
      when(mockAccessService.readOverrides(*[ApplicationId])).thenReturn(eventualOverridesResponse)

    lazy val accessController = new AccessController(StrideGatekeeperRoleAuthorisationServiceMock.aMock, mockApplicationService, mockAccessService, mockControllerComponents)

    StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

    def invokeAccessControllerReadScopesWith(applicationId: ApplicationId): Future[Result] =
      accessController.readScopes(applicationId)(fakeRequest)

    def invokeAccessControllerReadOverridesWith(applicationId: ApplicationId): Future[Result] =
      accessController.readOverrides(applicationId)(fakeRequest)
  }

  trait StandardFixture extends Fixture {
    when(mockApplicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](standardApp))
  }

  trait PrivilegedAndRopcFixture extends Fixture {

    def testWithPrivilegedAndRopc(testBlock: => Unit): Unit = {
      when(mockApplicationService.fetch(applicationId)).thenReturn(
        OptionT.pure[Future](
          privilegedApp
        ),
        OptionT.pure[Future](
          ropcApp
        )
      )
      testBlock
      testBlock
    }
  }

}
