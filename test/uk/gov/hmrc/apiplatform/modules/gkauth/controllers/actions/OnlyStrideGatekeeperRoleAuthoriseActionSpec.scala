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

package uk.gov.hmrc.apiplatform.modules.gkauth.controllers.actions

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.mvc.ControllerComponents
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.gkauth.services.{StrideGatekeeperRoleAuthorisationService, StrideGatekeeperRoleAuthorisationServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.controllers.OnlyStrideGatekeeperRoleAuthoriseAction
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.util._

class OnlyStrideGatekeeperRoleAuthoriseActionSpec extends AsyncHmrcSpec with StrideGatekeeperRoleAuthorisationServiceMockModule with ApplicationServiceMockModule {

  abstract class TestController(val cc: ControllerComponents)(implicit val executionContext: ExecutionContext) extends BackendController(cc)
      with OnlyStrideGatekeeperRoleAuthoriseAction {
    def applicationService: ApplicationService
    def strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService
    implicit val ec: ExecutionContext = executionContext

    def testMethod = requiresAuthentication() { _ =>
      Ok("Authenticated")
    }
  }

  trait Setup {
    val stubControllerComponents = Helpers.stubControllerComponents()
    val request                  = FakeRequest()

    lazy val underTest = new TestController(stubControllerComponents) {
      val applicationService: ApplicationService   = ApplicationServiceMock.aMock
      val strideGatekeeperRoleAuthorisationService = StrideGatekeeperRoleAuthorisationServiceMock.aMock
    }
  }

  "succeed when authorised" in new Setup {
    StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised
    val result = underTest.testMethod(request)
    status(result) shouldBe OK
  }

  "fail when not authorised" in new Setup {
    StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
    val result = underTest.testMethod(request)
    status(result) shouldBe UNAUTHORIZED
  }
}
