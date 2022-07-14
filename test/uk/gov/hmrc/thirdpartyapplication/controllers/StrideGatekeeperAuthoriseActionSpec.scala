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
import cats.data.OptionT
import cats.implicits._
import play.api.mvc._
import play.api.mvc.Results.Ok
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector

import scala.concurrent.ExecutionContext
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import akka.stream.testkit.NoMaterializer

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.config.AuthConfig
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles

class StrideGatekeeperAuthoriseActionSpec extends ControllerSpec with MockedAuthHelper {

  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = NoMaterializer

  abstract class TestAuthoriseAction(val cc: ControllerComponents)(implicit val executionContext: ExecutionContext) extends BackendController(cc) with JsonUtils with StrideGatekeeperAuthorise with StrideGatekeeperAuthoriseAction {
    def applicationService: ApplicationService
    def authConfig: AuthConfig
    def strideAuthRoles: StrideAuthRoles
    def strideAuthConnector: StrideAuthConnector
    implicit def ec = executionContext
  }

  trait Setup {
    val stubControllerComponents = Helpers.stubControllerComponents()

    lazy val underTest = new TestAuthoriseAction(stubControllerComponents) {
      implicit val headerCarrier: HeaderCarrier           = HeaderCarrier()
      val strideAuthConnector: StrideAuthConnector        = mockStrideAuthConnector
      val applicationService: ApplicationService          = mock[ApplicationService]
      val authConfig: AuthConfig                          = provideAuthConfig()
      val strideAuthRoles                                 = fakeStrideRoles
    }
    val request   = FakeRequest()

    val parse = stubControllerComponents.parsers

    def mockFetchApplicationToReturn(id: ApplicationId, application: Option[ApplicationResponse]) =
      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.fromOption(application))
  }
  
    "Authenticated by Stride Gatekeeper Role" should {

    "accept the request when the gatekeeper is logged in" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = underTest.requiresAuthentication().async(_ => successful(Ok("")))(request)

      status(result) shouldBe OK
    }

    "throws SessionRecordNotFound when the gatekeeper is not logged in" in new Setup {

      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.requiresAuthentication().async(_ => successful(Ok("")))(request)))
    }
  }
}
