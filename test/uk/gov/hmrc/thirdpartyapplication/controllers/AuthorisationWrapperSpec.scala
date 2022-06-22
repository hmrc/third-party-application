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
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import play.api.mvc.Results.Ok
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode.APPLICATION_NOT_FOUND
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.{PRIVILEGED, ROPC}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import akka.stream.testkit.NoMaterializer

import java.time.LocalDateTime

class AuthorisationWrapperSpec(implicit val executionContext: ExecutionContext) extends ControllerSpec {

  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = NoMaterializer
  val mockAuthConfig                           = mock[AuthConnector.Config]

  when(mockAuthConfig.enabled).thenReturn(true)

  abstract class TestAuthorisationWrapper(val cc: ControllerComponents)(implicit val executionContext: ExecutionContext) extends BackendController(cc) with AuthorisationWrapper {
    def applicationService: ApplicationService
    def authConfig: AuthConnector.Config
    def authConnector: AuthConnector
    implicit def ec = executionContext
  }

  trait Setup {
    val stubControllerComponents = Helpers.stubControllerComponents()

    val underTest = new TestAuthorisationWrapper(stubControllerComponents) {
      implicit val headerCarrier: HeaderCarrier           = HeaderCarrier()
      override val authConnector: AuthConnector           = mock[AuthConnector]
      override val applicationService: ApplicationService = mock[ApplicationService]
      override val authConfig: AuthConnector.Config       = mockAuthConfig
    }
    val request   = FakeRequest()

    val parse = stubControllerComponents.parsers

    def mockFetchApplicationToReturn(id: ApplicationId, application: Option[ApplicationResponse]) =
      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.fromOption(application))
  }

  "Authenticate for Access Type and Role" should {
    val ropcRequest       = postRequestWithAccess(Ropc())
    val privilegedRequest = postRequestWithAccess(Privileged())
    val standardRequest   = postRequestWithAccess(Standard())

    "accept the request when access type in the payload is PRIVILEGED and gatekeeper authenticated" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = underTest.requiresAuthenticationFor(PRIVILEGED).async(parse.json)(_ =>
        successful(Ok(""))
      )(privilegedRequest)

      status(result) shouldBe OK
    }

    "accept the request when access type in the payload is ROPC and user is authenticated" in new Setup {
      givenUserIsAuthenticated(underTest)
      status(underTest.requiresAuthenticationFor(ROPC).async(parse.json)(_ => successful(Ok("")))(ropcRequest)) shouldBe OK
    }

    "skip gatekeeper authentication for payload with STANDARD applications if the method only requires auth for priviledged app" in new Setup {

      val result = underTest.requiresAuthenticationFor(PRIVILEGED).async(parse.json)(_ =>
        successful(Ok(""))
      )(standardRequest)

      status(result) shouldBe OK
      verifyZeroInteractions(underTest.authConnector)
    }

    "throws SessionRecordNotFound when access type in the payload is PRIVILEGED and gatekeeper is not logged in" in new Setup {

      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.requiresAuthenticationFor(PRIVILEGED).async(parse.json)(_ =>
        successful(Ok(""))
      )(privilegedRequest)))
    }

    "throws SessionRecordNotFound when access type in the payload is ROPC and gatekeeper is not logged in" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.requiresAuthenticationFor(ROPC).async(parse.json)(_ => successful(Ok("")))(ropcRequest)))
    }
  }

  "Authenticate for Access Type, Role and Application ID" should {
    val applicationId         = ApplicationId.random
    val ropcApplication       = application(Ropc())
    val privilegedApplication = application(Privileged())
    val standardApplication   = application(Standard())

    "accept the request when access type of the application is PRIVILEGED and gatekeeper is logged in" in new Setup {

      mockFetchApplicationToReturn(applicationId, Some(privilegedApplication))

      givenUserIsAuthenticated(underTest)

      val result = underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => successful(Ok("")))(request)

      status(result) shouldBe OK
    }

    "accept the request when access type of the application is ROPC and gatekeeper is logged in" in new Setup {
      mockFetchApplicationToReturn(applicationId, Some(ropcApplication))
      givenUserIsAuthenticated(underTest)
      status(underTest.requiresAuthenticationFor(applicationId, ROPC).async(_ => successful(Ok("")))(request)) shouldBe OK
    }

    "skip gatekeeper authentication for STANDARD applications if the method only requires auth for priviledged app" in new Setup {

      mockFetchApplicationToReturn(applicationId, Some(standardApplication))

      val result = underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => successful(Ok("")))(request)

      status(result) shouldBe OK
      verifyZeroInteractions(underTest.authConnector)
    }

    "throws SessionRecordNotFound when access type of the application is PRIVILEGED and gatekeeper is not logged in" in new Setup {

      mockFetchApplicationToReturn(applicationId, Some(privilegedApplication))

      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => successful(Ok("")))(request)))
    }

    "throws SessionRecordNotFound when access type of the application is ROPC and gatekeeper is not logged in" in new Setup {
      mockFetchApplicationToReturn(applicationId, Some(ropcApplication))
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.requiresAuthenticationFor(applicationId, ROPC).async(_ => successful(Ok("")))(request)))
    }

    "return a 404 (Not Found) when the application doesn't exist" in new Setup {

      mockFetchApplicationToReturn(applicationId, None)

      val result = underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => successful(Ok("")))(request)

      status(result) shouldBe NOT_FOUND
      contentAsJson(result) shouldBe JsErrorResponse(APPLICATION_NOT_FOUND, s"application ${applicationId.value} doesn't exist")
    }
  }

  "Authenticated by Role" should {

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

  private def postRequestWithAccess(access: Access) = FakeRequest("POST", "/").withBody(Json.obj("access" -> access).as[JsValue])

  private def application(access: Access) = {
    val grantLengthInDays = 547
    ApplicationResponse(
      ApplicationId.random,
      ClientId("clientId"),
      "gatewayId",
      "name",
      "PRODUCTION",
      None,
      Set(),
      LocalDateTime.now,
      Some(LocalDateTime.now),
      grantLengthInDays,
      access = access
    )
  }

}
