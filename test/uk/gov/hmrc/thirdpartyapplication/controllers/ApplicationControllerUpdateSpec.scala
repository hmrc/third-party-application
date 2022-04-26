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
import com.github.t3hnar.bcrypt._
import org.scalatest.prop.TableDrivenPropertyChecks
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.FakeRequest
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.services.CredentialService
import uk.gov.hmrc.thirdpartyapplication.services.GatekeeperService
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import akka.stream.testkit.NoMaterializer
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.apiplatform.modules.upliftlinks.service.UpliftLinkService

import java.time.LocalDateTime

class ApplicationControllerUpdateSpec extends ControllerSpec
  with ApplicationStateUtil with TableDrivenPropertyChecks {

  import play.api.test.Helpers
  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = NoMaterializer

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    def canDeleteApplications() = true
    def enabled() = true

    val mockGatekeeperService: GatekeeperService = mock[GatekeeperService]
    val mockEnrolment: Enrolment = mock[Enrolment]
    val mockCredentialService: CredentialService = mock[CredentialService]
    val mockApplicationService: ApplicationService = mock[ApplicationService]
    val mockAuthConnector: AuthConnector = mock[AuthConnector]
    val mockSubscriptionService: SubscriptionService = mock[SubscriptionService]
    val mockSubmissionService: SubmissionsService = mock[SubmissionsService]
    val mockNamingService: UpliftNamingService = mock[UpliftNamingService]
    val mockUpliftLinkService: UpliftLinkService = mock[UpliftLinkService]

    val testAuthConfig: AuthConnector.Config =
      AuthConnector.Config(
        baseUrl = "",
        userRole = "USER",
        superUserRole = "SUPER",
        adminRole = "ADMIN",
        enabled = enabled(),
        canDeleteApplications = canDeleteApplications(),
        authorisationKey = "12345"
      )

    val applicationTtlInSecs = 1234
    val subscriptionTtlInSecs = 4321
    val config = ApplicationControllerConfig(applicationTtlInSecs, subscriptionTtlInSecs)

    val underTest = new ApplicationController(
      mockApplicationService,
      mockAuthConnector,
      testAuthConfig,
      mockCredentialService,
      mockSubscriptionService,
      config,
      mockGatekeeperService,
      mockSubmissionService,
      mockNamingService,
      mockUpliftLinkService,
      Helpers.stubControllerComponents())
  }

  trait SandboxDeleteApplications extends Setup {
    override def canDeleteApplications() = true
    override def enabled() = false
  }

  trait ProductionDeleteApplications extends Setup {
    override def canDeleteApplications() = false
    override def enabled() = true
  }

  trait PrivilegedAndRopcSetup extends Setup {

    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      givenUserIsAuthenticated(underTest)

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = true, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      givenUserIsNotAuthenticated(underTest)

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = false, testBlock)
    }

    private def testWithPrivilegedAndRopc(applicationId: ApplicationId, gatekeeperLoggedIn: Boolean, testBlock: => Unit): Unit = {
      when(underTest.applicationService.fetch(applicationId))
        .thenReturn(
          OptionT.pure[Future](aNewApplicationResponse(privilegedAccess)),
          OptionT.pure[Future](aNewApplicationResponse(ropcAccess))
        )
      testBlock
      testBlock
    }
  }

  val authTokenHeader: (String, String) = "authorization" -> "authorizationToken"

  val credentialServiceResponseToken: ApplicationTokenResponse =
    ApplicationTokenResponse(ClientId("111"), "222", List(ClientSecretResponse(ClientSecret("3333", hashedSecret = "3333".bcrypt(4)))))

  val collaborators: Set[Collaborator] = Set(
    Collaborator("admin@example.com", ADMINISTRATOR,UserId.random),
    Collaborator("dev@example.com", DEVELOPER, UserId.random))

  private val standardAccess = Standard(List("http://example.com/redirect"), Some("http://example.com/terms"), Some("http://example.com/privacy"))
  private val privilegedAccess = Privileged(scopes = Set("scope1"))
  private val ropcAccess = Ropc()

  "Update" should {
    val standardApplicationRequest = anUpdateApplicationRequest(standardAccess)
    val privilegedApplicationRequest = anUpdateApplicationRequest(privilegedAccess)
    val id = ApplicationId.random

    "fail with a 404 (not found) when a valid Privileged application and gatekeeper is not logged in" in new Setup {

      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.none)

      val result = underTest.update(id)(request.withBody(Json.toJson(privilegedApplicationRequest)))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 404 (not found) when id is provided but no application exists for that id" in new Setup {

      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.none)

      val result = underTest.update(id)(request.withBody(Json.toJson(standardApplicationRequest)))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 422 (unprocessable entity) when request exceeds maximum number of redirect URIs" in new Setup {
      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val updateApplicationRequestJson: String =
        """{
          "id" : "My ID",
          "name" : "My Application",
          "collaborators": [{"emailAddress": "admin@example.com","role": "ADMINISTRATOR"}],
          "access": {
            "accessType": "STANDARD",
            "redirectUris": [
              "http://localhost:8080/redirect1", "http://localhost:8080/redirect2",
              "http://localhost:8080/redirect3", "http://localhost:8080/redirect4",
              "http://localhost:8080/redirect5", "http://localhost:8080/redirect6"
              ],
            "overrides" : []
            }
          }"""
      val result = underTest.update(id)(request.withBody(Json.parse(updateApplicationRequestJson)))

      status(result) shouldBe UNPROCESSABLE_ENTITY
      (contentAsJson(result) \ "message").as[String] shouldBe "requirement failed: maximum number of redirect URIs exceeded"
    }
  }

  private def aNewApplicationResponse(access: Access = standardAccess, environment: Environment = Environment.PRODUCTION) = {
    val grantLengthInDays = 547
    new ApplicationResponse(
      ApplicationId.random,
      ClientId("clientId"),
      "gatewayId",
      "My Application",
      environment.toString,
      Some("Description"),
      collaborators,
      LocalDateTime.now,
      Some(LocalDateTime.now),
      grantLengthInDays,
      None,
      standardAccess.redirectUris,
      standardAccess.termsAndConditionsUrl,
      standardAccess.privacyPolicyUrl,
      access
    )
  }

  private def anUpdateApplicationRequest(access: Access) = UpdateApplicationRequest("My Application", access, Some("Description"))
}
