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
import org.scalatest.prop.TableDrivenPropertyChecks
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.FakeRequest
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.services.CredentialService
import uk.gov.hmrc.thirdpartyapplication.services.GatekeeperService
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.failed
import scala.concurrent.Future.successful
import akka.stream.testkit.NoMaterializer
import uk.gov.hmrc.thirdpartyapplication.util.UpliftRequestSamples
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.UpliftLinkServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.config.AuthConfig
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import java.time.LocalDateTime
import uk.gov.hmrc.thirdpartyapplication.config.AuthConfig
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector

class ApplicationControllerCreateSpec extends ControllerSpec
    with ApplicationStateUtil with TableDrivenPropertyChecks
    with UpliftRequestSamples
    with SubmissionsTestData {

  import play.api.test.Helpers
  import play.api.test.Helpers._

  implicit val v1writes = Json.writes[CreateApplicationRequestV1]
  implicit val v2writes = Json.writes[CreateApplicationRequestV2]

  implicit lazy val materializer: Materializer = NoMaterializer

  val collaborators: Set[Collaborator] = Set(
    Collaborator("admin@example.com", ADMINISTRATOR, UserId.random),
    Collaborator("dev@example.com", DEVELOPER, UserId.random)
  )

  private val standardAccess   = Standard(List("http://example.com/redirect"), Some("http://example.com/terms"), Some("http://example.com/privacy"))
  private val privilegedAccess = Privileged(scopes = Set("scope1"))
  private val ropcAccess       = Ropc()

  trait Setup extends SubmissionsServiceMockModule with UpliftLinkServiceMockModule {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    def canDeleteApplications() = true
    def enabled()               = true

    val mockGatekeeperService: GatekeeperService     = mock[GatekeeperService]
    val mockEnrolment: Enrolment                     = mock[Enrolment]
    val mockCredentialService: CredentialService     = mock[CredentialService]
    val mockApplicationService: ApplicationService   = mock[ApplicationService]
    val mockAuthConnector: StrideAuthConnector       = mock[StrideAuthConnector]
    val mockSubscriptionService: SubscriptionService = mock[SubscriptionService]
    val mockNamingService: UpliftNamingService       = mock[UpliftNamingService]

    val mockAuthConfig: AuthConfig = mock[AuthConfig]
    when(mockAuthConfig.enabled).thenReturn(enabled())
    when(mockAuthConfig.userRole).thenReturn("USER")
    when(mockAuthConfig.superUserRole).thenReturn("SUPER")
    when(mockAuthConfig.adminRole).thenReturn("ADMIN")
    when(mockAuthConfig.canDeleteApplications).thenReturn(canDeleteApplications())

    val applicationTtlInSecs  = 1234
    val subscriptionTtlInSecs = 4321
    val config                = ApplicationControllerConfig(applicationTtlInSecs, subscriptionTtlInSecs)

    val underTest = new ApplicationController(
      mockApplicationService,
      mockAuthConnector,
      mockAuthConfig,
      mockCredentialService,
      mockSubscriptionService,
      config,
      mockGatekeeperService,
      SubmissionsServiceMock.aMock,
      mockNamingService,
      UpliftLinkServiceMock.aMock,
      Helpers.stubControllerComponents()
    )
  }

  "Create" should {
    val standardApplicationRequest   = aCreateApplicationRequestV2(StandardAccessDataToCopy(standardAccess.redirectUris))
    val standardApplicationRequestV1 = aCreateApplicationRequestV1(standardAccess)
    val privilegedApplicationRequest = aCreateApplicationRequestV1(privilegedAccess)
    val ropcApplicationRequest       = aCreateApplicationRequestV1(ropcAccess)

    val standardApplicationResponse   = CreateApplicationResponse(aNewApplicationResponse())
    val totp                          = TotpSecret("pTOTP")
    val privilegedApplicationResponse = CreateApplicationResponse(aNewApplicationResponse(privilegedAccess), Some(totp))
    val ropcApplicationResponse       = CreateApplicationResponse(aNewApplicationResponse(ropcAccess))

    "succeed with a 201 (Created) for a valid Standard application request when service responds successfully" in new Setup {
      when(underTest.applicationService.create(eqTo(standardApplicationRequest))(*)).thenReturn(successful(standardApplicationResponse))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(*[ApplicationId], *)(*)).thenReturn(successful(HasSucceeded))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequest)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(standardApplicationRequest))(*)
    }

    "succeed with a 201 (Created) for a valid Standard application request when service responds successfully to legacy uplift" in new Setup {
      when(underTest.applicationService.create(eqTo(standardApplicationRequestV1))(*)).thenReturn(successful(standardApplicationResponse))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(*[ApplicationId], *)(*)).thenReturn(successful(HasSucceeded))

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequestV1)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(standardApplicationRequestV1))(*)
    }

    "succeed with a 201 (Created) for a valid Privileged application request when gatekeeper is logged in and service responds successfully" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(underTest.applicationService.create(eqTo(privilegedApplicationRequest))(*)).thenReturn(successful(privilegedApplicationResponse))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(*[ApplicationId], *)(*)).thenReturn(successful(HasSucceeded))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.create()(request.withBody(Json.toJson(privilegedApplicationRequest)))

      (contentAsJson(result) \ "totp").as[TotpSecret] shouldBe totp
      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(privilegedApplicationRequest))(*)
    }

    "succeed with a 201 (Created) for a valid ROPC application request when gatekeeper is logged in and service responds successfully" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(underTest.applicationService.create(eqTo(ropcApplicationRequest))(*)).thenReturn(successful(ropcApplicationResponse))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(*[ApplicationId], *)(*)).thenReturn(successful(HasSucceeded))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.create()(request.withBody(Json.toJson(ropcApplicationRequest)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(ropcApplicationRequest))(*)
    }

    "succeed with a 201 (Created) for a valid Standard application request with one subscription when service responds successfully" in new Setup {
      val testApi                               = ApiIdentifier.random
      val apis                                  = Set(testApi)
      val applicationRequestWithOneSubscription = standardApplicationRequest.copy(upliftRequest = makeUpliftRequest(apis))

      when(underTest.applicationService.create(eqTo(applicationRequestWithOneSubscription))(*)).thenReturn(successful(standardApplicationResponse))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(standardApplicationResponse.application.id), eqTo(testApi))(*)).thenReturn(successful(
        HasSucceeded
      ))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.create()(request.withBody(Json.toJson(applicationRequestWithOneSubscription)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(applicationRequestWithOneSubscription))(*)
      verify(mockSubscriptionService, times(1)).createSubscriptionForApplicationMinusChecks(eqTo(standardApplicationResponse.application.id), eqTo(testApi))(*)
    }

    "succeed with a 201 (Created) for a valid Standard application request with multiple subscriptions when service responds successfully" in new Setup {
      val testApi                                = ApiIdentifier.random
      val anotherTestApi                         = ApiIdentifier.random
      val apis                                   = Set(testApi, anotherTestApi)
      val applicationRequestWithTwoSubscriptions = standardApplicationRequest.copy(upliftRequest = makeUpliftRequest(apis))

      when(underTest.applicationService.create(eqTo(applicationRequestWithTwoSubscriptions))(*)).thenReturn(successful(standardApplicationResponse))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      apis.map(api =>
        when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(standardApplicationResponse.application.id), eqTo(api))(*)).thenReturn(successful(HasSucceeded))
      )

      val result = underTest.create()(request.withBody(Json.toJson(applicationRequestWithTwoSubscriptions)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(applicationRequestWithTwoSubscriptions))(*)
      verify(mockSubscriptionService, times(2)).createSubscriptionForApplicationMinusChecks(*[ApplicationId], *[ApiIdentifier])(*)
    }

    "fail with a 401 (Unauthorized) for a valid Privileged application request when gatekeeper is not logged in" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.create()(request.withBody(Json.toJson(privilegedApplicationRequest)))))

      verify(underTest.applicationService, never).create(any[CreateApplicationRequest])(*)
    }

    "fail with a 401 (Unauthorized) for a valid ROPC application request when gatekeeper is not logged in" in new Setup {
      givenUserIsNotAuthenticated(underTest)

      assertThrows[SessionRecordNotFound](await(underTest.create()(request.withBody(Json.toJson(ropcApplicationRequest)))))

      verify(underTest.applicationService, never).create(any[CreateApplicationRequest])(*)
    }

    "fail with a 409 (Conflict) for a privileged application when the name already exists for another production application" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(underTest.applicationService.create(eqTo(privilegedApplicationRequest))(*))
        .thenReturn(failed(ApplicationAlreadyExists("appName")))

      val result = underTest.create()(request.withBody(Json.toJson(privilegedApplicationRequest)))

      status(result) shouldBe CONFLICT
      contentAsJson(result) shouldBe JsErrorResponse(APPLICATION_ALREADY_EXISTS, "Application already exists with name: appName")
    }

    "fail with a 422 (unprocessable entity) when unexpected json is provided" in new Setup {
      val body = """{ "json": "invalid" }"""

      val result = underTest.create()(request.withBody(Json.parse(body)))

      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "fail with a 422 (unprocessable entity) when duplicate email is provided" in new Setup {
      val id = UserId.random

      val body: String =
        s"""{
           |"name" : "My Application",
           |"environment": "PRODUCTION",
           |"access" : {
           |  "accessType" : "STANDARD",
           |  "redirectUris" : [],
           |  "overrides" : []
           |},
           |"collaborators": [
           |{"emailAddress": "admin@example.com","role": "ADMINISTRATOR", "userId": "${id.value}"},
           |{"emailAddress": "ADMIN@example.com","role": "ADMINISTRATOR", "userId": "${id.value}"}
           |]
           |}""".stripMargin.replaceAll("\n", "")

      val result = underTest.create()(request.withBody(Json.parse(body)))

      status(result) shouldBe UNPROCESSABLE_ENTITY
      (contentAsJson(result) \ "message").as[String] shouldBe "requirement failed: duplicate email in collaborator"
    }

    "fail with a 422 (unprocessable entity) when request exceeds maximum number of redirect URIs" in new Setup {
      val createApplicationRequestJson: String =
        s"""{
          "name" : "My Application",
          "environment": "PRODUCTION",
          "access": {
            "accessType": "STANDARD",
            "redirectUris": [
              "http://localhost:8080/redirect1", "http://localhost:8080/redirect2",
              "http://localhost:8080/redirect3", "http://localhost:8080/redirect4",
              "http://localhost:8080/redirect5", "http://localhost:8080/redirect6"
            ],
            "overrides" : []
          },
          "collaborators": [{"emailAddress": "admin@example.com","role": "ADMINISTRATOR", "userId": "${UserId.random.value}"}]
          }"""

      val result = underTest.create()(request.withBody(Json.parse(createApplicationRequestJson)))

      status(result) shouldBe UNPROCESSABLE_ENTITY
      (contentAsJson(result) \ "message").as[String] shouldBe "requirement failed: maximum number of redirect URIs exceeded"
    }

    "fail with a 422 (unprocessable entity) when incomplete json is provided" in new Setup {
      val body = """{ "name": "myapp" }"""

      val result = underTest.create()(request.withBody(Json.parse(body)))

      status(result) shouldBe UNPROCESSABLE_ENTITY

    }

    "fail with a 422 (unprocessable entity) and correct body when incorrect role is used" in new Setup {
      val body: String =
        s"""{
           |"name" : "My Application",
           |"description" : "Description",
           |"environment": "PRODUCTION",
           |"redirectUris": ["http://example.com/redirect"],
           |"termsAndConditionsUrl": "http://example.com/terms",
           |"privacyPolicyUrl": "http://example.com/privacy",
           |"collaborators": [
           |{
           |"emailAddress": "admin@example.com",
           |"role": "ADMINISTRATOR",
           |"userId": "${UserId.random.value}"
           |},
           |{
           |"emailAddress": "dev@example.com",
           |"role": "developer",
           |"userId": "${UserId.random.value}"
           |}]
           |}""".stripMargin.replaceAll("\n", "")

      val result = underTest.create()(request.withBody(Json.parse(body)))

      val expected: String =
        s"""{
           |"code": "INVALID_REQUEST_PAYLOAD",
           |"message": "Enumeration expected of type: 'Role$$', but it does not contain 'developer'"
           |}""".stripMargin.replaceAll("\n", "")

      status(result) shouldBe UNPROCESSABLE_ENTITY
      contentAsJson(result) shouldBe Json.toJson(Json.parse(expected))
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      when(underTest.applicationService.create(eqTo(standardApplicationRequest))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequest)))

      status(result) shouldBe INTERNAL_SERVER_ERROR
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

  private def aCreateApplicationRequestV1(access: Access) = CreateApplicationRequestV1(
    "My Application",
    access,
    Some("Description"),
    Environment.PRODUCTION,
    Set(
      Collaborator("admin@example.com", ADMINISTRATOR, UserId.random),
      Collaborator("dev@example.com", ADMINISTRATOR, UserId.random)
    ),
    Some(Set(ApiIdentifier.random))
  )

  private def aCreateApplicationRequestV2(access: StandardAccessDataToCopy) = CreateApplicationRequestV2(
    "My Application",
    access,
    Some("Description"),
    Environment.PRODUCTION,
    Set(
      Collaborator("admin@example.com", ADMINISTRATOR, UserId.random),
      Collaborator("dev@example.com", ADMINISTRATOR, UserId.random)
    ),
    makeUpliftRequest(ApiIdentifier.random),
    "bob@example.com",
    ApplicationId.random
  )
}
