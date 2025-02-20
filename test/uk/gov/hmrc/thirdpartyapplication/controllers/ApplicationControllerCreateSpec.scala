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
import scala.concurrent.Future.{failed, successful}

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer
import org.scalatest.prop.TableDrivenPropertyChecks

import play.api.libs.json.{Json, OWrites}
import play.api.mvc._
import play.api.test.FakeRequest
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Environment._
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{UserId, _}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationStateFixtures, _}
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.{
  CreateApplicationRequest,
  CreateApplicationRequestV1,
  CreateApplicationRequestV2,
  CreationAccess,
  StandardAccessDataToCopy
}
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationServiceMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.UpliftLinkServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.services.{CredentialService, GatekeeperService, SubscriptionService}
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class ApplicationControllerCreateSpec extends ControllerSpec
    with ApplicationStateFixtures with TableDrivenPropertyChecks
    with UpliftRequestSamples
    with SubmissionsTestData
    with ApplicationWithCollaboratorsFixtures
    with CollaboratorTestData {

  import play.api.test.Helpers
  import play.api.test.Helpers._

  implicit val v1writes: OWrites[CreateApplicationRequestV1] = Json.writes[CreateApplicationRequestV1]
  implicit val v2writes: OWrites[CreateApplicationRequestV2] = Json.writes[CreateApplicationRequestV2]

  implicit lazy val materializer: Materializer = NoMaterializer

  val collaborators: Set[Collaborator] = Set(
    "admin@example.com".admin(),
    "dev@example.com".developer()
  )

  private val myPrivilegedAccess = Access.Privileged(scopes = Set("scope1"))

  trait Setup
      extends SubmissionsServiceMockModule
      with UpliftLinkServiceMockModule
      with StrideGatekeeperRoleAuthorisationServiceMockModule
      with ApplicationServiceMockModule {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    val mockGatekeeperService: GatekeeperService     = mock[GatekeeperService]
    val mockEnrolment: Enrolment                     = mock[Enrolment]
    val mockCredentialService: CredentialService     = mock[CredentialService]
    val mockSubscriptionService: SubscriptionService = mock[SubscriptionService]
    val mockNamingService: UpliftNamingService       = mock[UpliftNamingService]

    val applicationTtlInSecs  = 1234
    val subscriptionTtlInSecs = 4321
    val config                = ApplicationControllerConfig(applicationTtlInSecs, subscriptionTtlInSecs)

    lazy val underTest = new ApplicationController(
      StrideGatekeeperRoleAuthorisationServiceMock.aMock,
      AuthControlConfig(true, false, "key"),
      ApplicationServiceMock.aMock,
      mockCredentialService,
      mockSubscriptionService,
      config,
      SubmissionsServiceMock.aMock,
      mockNamingService,
      UpliftLinkServiceMock.aMock,
      Helpers.stubControllerComponents()
    )
  }

  "Create" should {
    val standardApplicationRequest   = aCreateApplicationRequestV2(StandardAccessDataToCopy(standardAccessOne.redirectUris))
    val standardApplicationRequestV1 = aCreateApplicationRequestV1(CreationAccess.Standard)
    val privilegedApplicationRequest = aCreateApplicationRequestV1(CreationAccess.Privileged)

    val standardApplicationResponse   = CreateApplicationResponse(standardApp.withAccess(standardAccessOne))
    val totp                          = CreateApplicationResponse.TotpSecret("pTOTP")
    val privilegedApplicationResponse = CreateApplicationResponse(privilegedApp.withAccess(myPrivilegedAccess), Some(totp))

    "succeed with a 201 (Created) for a valid Access.Standard application request when service responds successfully" in new Setup {
      ApplicationServiceMock.Create.onRequestReturn(standardApplicationRequest)(standardApplicationResponse)
      when(mockSubscriptionService.updateApplicationForApiSubscription(*[ApplicationId], *[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequest)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(standardApplicationRequest))(*)
    }

    "succeed with a 201 (Created) for a valid Access.Standard application request when service responds successfully to legacy uplift" in new Setup {
      ApplicationServiceMock.Create.onRequestReturn(standardApplicationRequestV1)(standardApplicationResponse)
      when(mockSubscriptionService.updateApplicationForApiSubscription(*[ApplicationId], *[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequestV1)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(standardApplicationRequestV1))(*)
    }

    "succeed with a 201 (Created) for a valid Access.Privileged application request when gatekeeper is logged in and service responds successfully" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised
      ApplicationServiceMock.Create.onRequestReturn(privilegedApplicationRequest)(privilegedApplicationResponse)
      when(mockSubscriptionService.updateApplicationForApiSubscription(*[ApplicationId], *[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.create()(request.withBody(Json.toJson(privilegedApplicationRequest)))

      (contentAsJson(result) \ "totp").as[CreateApplicationResponse.TotpSecret] shouldBe totp
      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(privilegedApplicationRequest))(*)
    }

    "succeed with a 201 (Created) for a valid Access.Standard application request with one subscription when service responds successfully" in new Setup {
      val testApi                               = ApiIdentifier.random
      val apis                                  = Set(testApi)
      val applicationRequestWithOneSubscription = standardApplicationRequest.copy(upliftRequest = makeUpliftRequest(apis))

      ApplicationServiceMock.Create.onRequestReturn(applicationRequestWithOneSubscription)(standardApplicationResponse)
      when(mockSubscriptionService.updateApplicationForApiSubscription(*[ApplicationId], *[ApplicationName], *, *)(*)).thenReturn(successful(
        HasSucceeded
      ))
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.create()(request.withBody(Json.toJson(applicationRequestWithOneSubscription)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(applicationRequestWithOneSubscription))(*)
      verify(mockSubscriptionService, times(1)).updateApplicationForApiSubscription(
        eqTo(standardApplicationResponse.application.id),
        eqTo(standardApplicationResponse.application.name),
        eqTo(standardApplicationResponse.application.collaborators),
        eqTo(testApi)
      )(*)
    }

    "succeed with a 201 (Created) for a valid Access.Standard application request with multiple subscriptions when service responds successfully" in new Setup {
      val testApi                                = ApiIdentifier.random
      val anotherTestApi                         = ApiIdentifier.random
      val apis                                   = Set(testApi, anotherTestApi)
      val applicationRequestWithTwoSubscriptions = standardApplicationRequest.copy(upliftRequest = makeUpliftRequest(apis))

      ApplicationServiceMock.Create.onRequestReturn(applicationRequestWithTwoSubscriptions)(standardApplicationResponse)
      UpliftLinkServiceMock.CreateUpliftLink.thenReturn(standardApplicationRequest.sandboxApplicationId, standardApplicationResponse.application.id)
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      apis.map(api =>
        when(mockSubscriptionService.updateApplicationForApiSubscription(*[ApplicationId], *[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))
      )

      val result = underTest.create()(request.withBody(Json.toJson(applicationRequestWithTwoSubscriptions)))

      status(result) shouldBe CREATED
      verify(underTest.applicationService).create(eqTo(applicationRequestWithTwoSubscriptions))(*)
      verify(mockSubscriptionService, times(2)).updateApplicationForApiSubscription(
        eqTo(standardApplicationResponse.application.id),
        eqTo(standardApplicationResponse.application.name),
        eqTo(standardApplicationResponse.application.collaborators),
        *[ApiIdentifier]
      )(*)
    }

    "fail with a 401 (Unauthorized) for a valid Access.Privileged application request when gatekeeper is not logged in" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      val result = underTest.create()(request.withBody(Json.toJson(privilegedApplicationRequest)))
      status(result) shouldBe UNAUTHORIZED

      verify(underTest.applicationService, never).create(any[CreateApplicationRequest])(*)
    }

    "fail with a 409 (Conflict) for a privileged application when the name already exists for another production application" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

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
           |  "accessType" : "STANDARD"
           |},
           |"collaborators": [
           |{"emailAddress": "admin@example.com","role": "ADMINISTRATOR", "userId": "${id.value}"},
           |{"emailAddress": "admin@example.com","role": "ADMINISTRATOR", "userId": "${UserId.random}"}
           |]
           |}""".stripMargin.replaceAll("\n", "")

      val result = underTest.create()(request.withBody(Json.parse(body)))

      status(result) shouldBe UNPROCESSABLE_ENTITY
      (contentAsJson(result) \ "message").as[String] shouldBe "requirement failed: duplicate email in collaborator"
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
           |"access" : {
           |  "redirectUris" : [ "https://example.com/redirect" ],
           |  "postLogoutRedirectUris" : [],
           |  "overrides" : [ ]
           |},
           |"upliftRequest" : {
           |  "sellResellOrDistribute" : "Yes",
           |  "subscriptions" : [ {
           |    "context" : "itkdcZFWi4",
           |    "version" : "477.148"
           |  } ]
           |},
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

      status(result) shouldBe UNPROCESSABLE_ENTITY
      val content = contentAsJson(result).toString
      content should include(""""code":"INVALID_REQUEST_PAYLOAD"""")
      content should include(""""developer is not a recognised role"""")
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      when(underTest.applicationService.create(eqTo(standardApplicationRequest))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequest)))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  private def aCreateApplicationRequestV1(access: CreationAccess) = CreateApplicationRequestV1(
    ApplicationName("My Application"),
    access,
    Some("Description"),
    Environment.PRODUCTION,
    Set(
      "admin@example.com".admin(),
      "dev@example.com".developer()
    ),
    Some(Set(ApiIdentifier.random))
  )

  private def aCreateApplicationRequestV2(access: StandardAccessDataToCopy) = CreateApplicationRequestV2(
    ApplicationName("My Application"),
    access,
    Some("Description"),
    Environment.PRODUCTION,
    Set(
      "admin@example.com".admin(),
      "dev@example.com".developer()
    ),
    makeUpliftRequest(ApiIdentifier.random),
    "bob@example.com",
    ApplicationId.random
  )
}
