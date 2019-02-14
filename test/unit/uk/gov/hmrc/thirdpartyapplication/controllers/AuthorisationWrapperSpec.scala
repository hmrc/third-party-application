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

import controllers.Default
import org.apache.http.HttpStatus.{SC_FORBIDDEN, SC_NOT_FOUND, SC_OK}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.BodyParsers
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode.{APPLICATION_NOT_FOUND, FORBIDDEN}
import uk.gov.hmrc.thirdpartyapplication.controllers.{AuthorisationWrapper, JsErrorResponse}
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.{PRIVILEGED, ROPC}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.time.DateTimeUtils
import unit.uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._

class AuthorisationWrapperSpec extends UnitSpec with MockitoSugar with WithFakeApplication {

  implicit lazy val materializer = fakeApplication.materializer

  trait Setup {
    val underTest = new AuthorisationWrapper {
      implicit val headerCarrier: HeaderCarrier = HeaderCarrier()
      override val authConnector: AuthConnector = mock[AuthConnector]
      override val applicationService: ApplicationService = mock[ApplicationService]
      override val authConfig: AuthConfig = mock[AuthConfig]
    }

    def mockFetchApplicationToReturn(id: UUID, application: Option[ApplicationResponse]) =
      when(underTest.applicationService.fetch(id)).thenReturn(application)
  }

  "Authenticate for Access Type and Role" should {
    val ropcRequest = postRequestWithAccess(Ropc())
    val privilegedRequest = postRequestWithAccess(Privileged())
    val standardRequest = postRequestWithAccess(Standard())

    "accept the request when access type in the payload is PRIVILEGED and gatekeeper authenticated" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = await(underTest.requiresAuthenticationFor(PRIVILEGED).async(BodyParsers.parse.json)(_ =>
        Default.Ok(""))(privilegedRequest)
      )

      status(result) shouldBe SC_OK
    }

    "accept the request when access type in the payload is ROPC and user is authenticated" in new Setup {
      givenUserIsAuthenticated(underTest)
      status(underTest.requiresAuthenticationFor(ROPC).async(BodyParsers.parse.json)(_ => Default.Ok(""))(ropcRequest)) shouldBe SC_OK
    }

    "skip gatekeeper authentication for payload with STANDARD applications" in new Setup {

      val result = await(underTest.requiresAuthenticationFor(PRIVILEGED).async(BodyParsers.parse.json)(_ =>
        Default.Ok(""))(standardRequest)
      )

      status(result) shouldBe SC_OK
      verifyZeroInteractions(underTest.authConnector)
    }

    "return a 403 (forbidden) response when access type in the payload is PRIVILEGED and gatekeeper is not logged in" in new Setup {

      givenUserIsNotAuthenticated(underTest)

      val result = await(underTest.requiresAuthenticationFor(PRIVILEGED).async(BodyParsers.parse.json)(_ =>
        Default.Ok(""))(privilegedRequest)
      )

      status(result) shouldBe SC_FORBIDDEN
      jsonBodyOf(result) shouldBe JsErrorResponse(FORBIDDEN, "Insufficient enrolments")
    }

    "return a 403 (forbidden) response when access type in the payload is ROPC and gatekeeper is not logged in" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      val result = await(underTest.requiresAuthenticationFor(ROPC).async(BodyParsers.parse.json)(_ => Default.Ok(""))(ropcRequest))

      status(result) shouldBe SC_FORBIDDEN
      jsonBodyOf(result) shouldBe JsErrorResponse(FORBIDDEN, "Insufficient enrolments")
    }
  }

  "Authenticate for Access Type, Role and Application ID" should {
    val applicationId = UUID.randomUUID
    val ropcApplication = application(Ropc())
    val privilegedApplication = application(Privileged())
    val standardApplication = application(Standard())

    "accept the request when access type of the application is PRIVILEGED and gatekeeper is logged in" in new Setup {

      mockFetchApplicationToReturn(applicationId, Some(privilegedApplication))

      givenUserIsAuthenticated(underTest)

      val result = await(underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => Default.Ok(""))(FakeRequest()))

      status(result) shouldBe SC_OK
    }

    "accept the request when access type of the application is ROPC and gatekeeper is logged in" in new Setup {
      mockFetchApplicationToReturn(applicationId, Some(ropcApplication))
      givenUserIsAuthenticated(underTest)
      status(underTest.requiresAuthenticationFor(applicationId, ROPC).async(_ => Default.Ok(""))(FakeRequest())) shouldBe SC_OK
    }

    "skip gatekeeper authentication for STANDARD applications" in new Setup {

      mockFetchApplicationToReturn(applicationId, Some(standardApplication))

      val result = await(underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => Default.Ok(""))(FakeRequest()))


      status(result) shouldBe SC_OK
      verifyZeroInteractions(underTest.authConnector)
    }

    "return a 403 (forbidden) response when access type of the application is PRIVILEGED and gatekeeper is not logged in" in new Setup {

      mockFetchApplicationToReturn(applicationId, Some(privilegedApplication))

      givenUserIsNotAuthenticated(underTest)

      val result = await(underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => Default.Ok(""))(FakeRequest()))

      status(result) shouldBe SC_FORBIDDEN
      jsonBodyOf(result) shouldBe JsErrorResponse(FORBIDDEN, "Insufficient enrolments")
    }

    "return a 403 (forbidden) response when access type of the application is ROPC and gatekeeper is not logged in" in new Setup {
      mockFetchApplicationToReturn(applicationId, Some(ropcApplication))
      givenUserIsNotAuthenticated(underTest)

      val result = await(underTest.requiresAuthenticationFor(applicationId, ROPC).async(_ => Default.Ok(""))(FakeRequest()))

      status(result) shouldBe SC_FORBIDDEN
      jsonBodyOf(result) shouldBe JsErrorResponse(FORBIDDEN, "Insufficient enrolments")
    }

    "return a 404 (Not Found) when the application doesn't exist" in new Setup {

      mockFetchApplicationToReturn(applicationId, None)

      val result = await(underTest.requiresAuthenticationFor(applicationId, PRIVILEGED).async(_ => Default.Ok(""))(FakeRequest()))

      status(result) shouldBe SC_NOT_FOUND
      jsonBodyOf(result) shouldBe JsErrorResponse(APPLICATION_NOT_FOUND, s"application $applicationId doesn't exist")
    }
  }

  "Authenticated by Role" should {

    "accept the request when the gatekeeper is logged in" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = await(underTest.requiresAuthentication().async(_ => Default.Ok(""))(FakeRequest()))

      status(result) shouldBe SC_OK
    }

    "return a 403 (forbidden) response when the gatekeeper is not logged in" in new Setup {

      givenUserIsNotAuthenticated(underTest)

      val result = await(underTest.requiresAuthentication().async(_ => Default.Ok(""))(FakeRequest()))

      status(result) shouldBe SC_FORBIDDEN
      jsonBodyOf(result) shouldBe JsErrorResponse(FORBIDDEN, "Insufficient enrolments")
    }
  }

  private def postRequestWithAccess(access: Access) = FakeRequest("POST", "/").withBody(Json.obj("access" -> access).as[JsValue])

  private def application(access: Access) = ApplicationResponse(UUID.randomUUID, "clientId", "name", "PRODUCTION", None, Set(), DateTimeUtils.now, access = access)

}