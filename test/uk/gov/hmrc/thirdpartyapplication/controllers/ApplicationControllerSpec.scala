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
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer
import org.scalatest.prop.TableDrivenPropertyChecks

import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import play.api.test.{FakeRequest, Helpers}
import play.mvc.Http.HeaderNames
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.common.domain.models.FullName
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.{ApplicationNameValidationResult, GetAppsForAdminOrRIRequest}
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationServiceMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.UpliftLinkServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.SubscriptionData
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationServiceMockModule, QueryServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication.asAppWithCollaborators
import uk.gov.hmrc.thirdpartyapplication.models.{ConfirmSetupCompleteRequest, DeleteApplicationRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.services.{CredentialService, GatekeeperService, SubscriptionService}
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class ApplicationControllerSpec
    extends ControllerSpec
    with TableDrivenPropertyChecks
    with StoredApplicationFixtures
    with ApplicationWithCollaboratorsFixtures
    with ApiIdentifierFixtures
    with CommonApplicationId
    with FixedClock {

  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = NoMaterializer

  trait Setup
      extends AuthConfigSetup
      with QueryServiceMockModule
      with SubmissionsServiceMockModule
      with UpliftLinkServiceMockModule
      with StrideGatekeeperRoleAuthorisationServiceMockModule
      with ApplicationServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123", "USER-AGENT" -> "testme")

    val mockGatekeeperService: GatekeeperService     = mock[GatekeeperService]
    val mockEnrolment: Enrolment                     = mock[Enrolment]
    val mockCredentialService: CredentialService     = mock[CredentialService]
    val mockSubscriptionService: SubscriptionService = mock[SubscriptionService]
    val mockUpliftNamingService: UpliftNamingService = mock[UpliftNamingService]

    val applicationTtlInSecs  = 1234
    val subscriptionTtlInSecs = 4321
    val config                = ApplicationControllerConfig(applicationTtlInSecs, subscriptionTtlInSecs)

    lazy val underTest = new ApplicationController(
      StrideGatekeeperRoleAuthorisationServiceMock.aMock,
      provideAuthConfig(),
      ApplicationServiceMock.aMock,
      mockCredentialService,
      mockSubscriptionService,
      config,
      SubmissionsServiceMock.aMock,
      mockUpliftNamingService,
      UpliftLinkServiceMock.aMock,
      QueryServiceMock.aMock,
      Helpers.stubControllerComponents()
    )
  }

  trait PrivilegedAndRopcSetup extends Setup {

    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = true, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = false, testBlock)
    }

    private def testWithPrivilegedAndRopc(applicationId: ApplicationId, gatekeeperLoggedIn: Boolean, testBlock: => Unit): Unit = {
      when(underTest.applicationService.fetch(applicationId))
        .thenReturn(
          OptionT.pure[Future](privilegedApp),
          OptionT.pure[Future](ropcApp)
        )
      testBlock
      testBlock
    }
  }

  trait ExtendedResponses {
    self: Setup =>

    val apiIdentifier1                                              = "api1".asIdentifier
    val apiIdentifier2                                              = "api2".asIdentifier
    val standardApplicationResponse: ApplicationWithSubscriptions   = standardApp.withSubscriptions(Set(apiIdentifier1, apiIdentifier2))
    val privilegedApplicationResponse: ApplicationWithSubscriptions = privilegedApp.withSubscriptions(Set.empty)
    val ropcApplicationResponse: ApplicationWithSubscriptions       = ropcApp.withSubscriptions(Set.empty)
  }

  val authTokenHeader: (String, String) = "authorization" -> "authorizationToken"

  val credentialServiceResponseToken: ApplicationToken =
    ApplicationToken(
      clientId = ClientId("111"),
      accessToken = "222",
      clientSecrets = List(ClientSecret(ClientSecret.Id.random, "222", createdOn = instant)),
      lastAccessTokenUsage = None
    )

  "update approval" should {
    val termsOfUseAgreement = TermsOfUseAgreement(LaxEmailAddress("test@example.com"), instant, "1.0".asVersion.value)
    val checkInformation    = CheckInformation(
      contactDetails = Some(ContactDetails(FullName("Tester"), LaxEmailAddress("test@example.com"), "12345677890")),
      termsOfUseAgreements = List(termsOfUseAgreement)
    )
    val id                  = ApplicationId.random

    "fail with a 404 (not found) when id is provided but no application exists for that id" in new Setup {
      ApplicationServiceMock.Fetch.thenReturnNothingFor(id)

      val result = underTest.updateCheck(id)(request.withBody(Json.toJson(checkInformation)))

      verifyErrorResult(result, NOT_FOUND, common.ErrorCode.APPLICATION_NOT_FOUND)
    }

    "successfully update approval information for application XYZ" in new Setup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      when(underTest.applicationService.fetch(eqTo(id))).thenReturn(OptionT.pure[Future](standardApp.withId(id)))
      when(underTest.applicationService.updateCheck(eqTo(id), eqTo(checkInformation))).thenReturn(successful(standardApp.withId(id)))

      val jsonBody: JsValue = Json.toJson(checkInformation)
      val result            = underTest.updateCheck(id)(request.withBody(jsonBody))

      status(result) shouldBe OK
    }
  }

  "fetch application" should {

    "succeed with a 200 (ok) if the application exists for the given id" in new Setup {
      ApplicationServiceMock.Fetch.thenReturnFor(applicationId)(standardApp)

      val result = underTest.fetch(applicationId)(request)

      status(result) shouldBe OK
    }

    "return the grant length formatted as a Period" in new Setup {
      ApplicationServiceMock.Fetch.thenReturnFor(applicationId)(standardApp)

      val result = underTest.fetch(applicationId)(request)

      (contentAsJson(result) \ "details" \ "grantLength").as[String] shouldBe "P547D"
      status(result) shouldBe OK
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      ApplicationServiceMock.Fetch.thenReturnNothingFor(applicationId)

      val result = underTest.fetch(applicationId)(request)

      verifyErrorResult(result, NOT_FOUND, common.ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      ApplicationServiceMock.Fetch.thenThrowFor(applicationId)(new RuntimeException("Expected test failure"))

      val result = underTest.fetch(applicationId)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  "confirmSetupComplete" should {
    "call applicationService correctly" in new Setup {
      val applicationId               = ApplicationId.random
      val emailAddress                = "bob@example.com".toLaxEmail
      val confirmSetupCompleteRequest = ConfirmSetupCompleteRequest(emailAddress)

      when(underTest.applicationService.confirmSetupComplete(eqTo(applicationId), eqTo(emailAddress))).thenReturn(successful(mock[StoredApplication]))

      val result = underTest.confirmSetupComplete(applicationId)(request.withBody(Json.toJson(confirmSetupCompleteRequest)))

      status(result) shouldBe NO_CONTENT
    }
  }

  "validate credentials" should {
    val validation = ValidationRequest(ClientId("clientId"), "clientSecret")
    val payload    = s"""{"clientId":"${validation.clientId.value}", "clientSecret":"${validation.clientSecret}"}"""

    "succeed with a 200 (ok) if the credentials are valid for an application" in new Setup {
      when(mockCredentialService.validateCredentials(validation)).thenReturn(OptionT.pure[Future](standardApp))

      val result = underTest.validateCredentials(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK
    }

    "fail with a 401 if credentials are invalid for an application" in new Setup {

      when(mockCredentialService.validateCredentials(validation)).thenReturn(OptionT.none)

      val result = underTest.validateCredentials(request.withBody(Json.parse(payload)))

      verifyErrorResult(result, UNAUTHORIZED, common.ErrorCode.INVALID_CREDENTIALS)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      when(mockCredentialService.validateCredentials(validation)).thenReturn(OptionT.liftF(failed(new RuntimeException("Expected test failure"))))

      val result = underTest.validateCredentials(request.withBody(Json.parse(payload)))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "validate name" should {
    "Allow a valid app when ChangeApplicationNameValidationRequest sent" in new Setup {
      val applicationName = "my valid app name"
      val appId           = ApplicationId.random
      val payload         = s"""{"nameToValidate":"${applicationName}", "applicationId" : "${appId.value.toString}" }"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ApplicationNameValidationResult.Valid))

      private val result = underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.toJson[ApplicationNameValidationResult](ApplicationNameValidationResult.Valid)

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName), eqTo(Some(appId)))
    }

    "Allow a valid app when NewApplicationNameValidationRequest sent" in new Setup {
      val applicationName = "my valid app name"
      val payload         = s"""{"nameToValidate":"${applicationName}"}"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ApplicationNameValidationResult.Valid))

      private val result = underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.toJson[ApplicationNameValidationResult](ApplicationNameValidationResult.Valid)

      verify(mockUpliftNamingService).validateApplicationName(*, eqTo(None))
    }

    "Reject an app name as it contains a block bit of text when NewApplicationNameValidationRequest sent" in new Setup {
      val applicationName = "my invalid HMRC app name"
      val payload         = s"""{"nameToValidate":"${applicationName}"}"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ApplicationNameValidationResult.Invalid))

      private val result = underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.toJson[ApplicationNameValidationResult](ApplicationNameValidationResult.Invalid)

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName), eqTo(None))
    }

    "Reject an app name as it contains a block bit of text when ChangeApplicationNameValidationRequest sent" in new Setup {
      val applicationName = "my invalid HMRC app name"
      val appId           = ApplicationId.random
      val payload         = s"""{"nameToValidate":"${applicationName}", "applicationId" : "${appId.value.toString}" }"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ApplicationNameValidationResult.Invalid))

      private val result = underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.toJson[ApplicationNameValidationResult](ApplicationNameValidationResult.Invalid)

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName), eqTo(Some(appId)))
    }

    "Reject an app name as it is a duplicate name when NewApplicationNameValidationRequest sent" in new Setup {
      val applicationName = "my duplicate app name"
      val payload         = s"""{"nameToValidate":"${applicationName}"}"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ApplicationNameValidationResult.Duplicate))

      private val result = underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.toJson[ApplicationNameValidationResult](ApplicationNameValidationResult.Duplicate)

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName), eqTo(None))
    }

    "Reject an app name as it is a duplicate name when ChangeApplicationNameValidationRequest sent" in new Setup {
      val applicationName = "my duplicate app name"
      val appId           = ApplicationId.random
      val payload         = s"""{"nameToValidate":"${applicationName}", "applicationId" : "${appId.value.toString}" }"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ApplicationNameValidationResult.Duplicate))

      private val result = underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.toJson[ApplicationNameValidationResult](ApplicationNameValidationResult.Duplicate)

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName), eqTo(Some(appId)))
    }
  }

  "isSubscribed" should {

    val applicationId: ApplicationId = ApplicationId.random
    val context                      = "context".asContext
    val version                      = "1.0".asVersion
    val api                          = ApiIdentifier(context, version)

    "succeed with a 200 (ok) when the application is subscribed to a given API version" in new Setup {

      when(mockSubscriptionService.isSubscribed(applicationId, api)).thenReturn(successful(true))

      val result = underTest.isSubscribed(applicationId, context, version)(request)

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(api)
      headers(result).get(HeaderNames.CACHE_CONTROL) shouldBe Some(s"max-age=$subscriptionTtlInSecs")
    }

    "fail with a 404 (not found) when the application is not subscribed to a given API version" in new Setup {

      when(mockSubscriptionService.isSubscribed(applicationId, api)).thenReturn(successful(false))

      val result = underTest.isSubscribed(applicationId, context, version)(request)

      status(result) shouldBe NOT_FOUND
      verifyErrorResult(result, NOT_FOUND, common.ErrorCode.SUBSCRIPTION_NOT_FOUND)
      headers(result).get(HeaderNames.CACHE_CONTROL) shouldBe None
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockSubscriptionService.isSubscribed(applicationId, api)).thenReturn(failed(new RuntimeException("something went wrong")))

      val result = underTest.isSubscribed(applicationId, context, version)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
      headers(result).get(HeaderNames.CACHE_CONTROL) shouldBe None
    }
  }

  "fetchAllSubscriptions by ID" should {

    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId)))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe NOT_FOUND
    }

    "succeed with a 200 (ok) when subscriptions are found for the application" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId)))
        .thenReturn(successful(Set(apiIdentifierOne)))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe OK
    }

    "succeed with a 200 (ok) when no subscriptions are found for the application" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId)))
        .thenReturn(successful(Set.empty))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId)))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "fetchAllSubscriptions" should {
    def aSubcriptionData(id: ApiIdentifier) = {
      SubscriptionData(id, Set(ApplicationId.random, ApplicationId.random))
    }

    "succeed with a 200 (ok) when subscriptions are found for the application" in new Setup {

      val subscriptionData = List(aSubcriptionData(apiIdentifierOne), aSubcriptionData(apiIdentifierTwo))

      when(mockSubscriptionService.fetchAllSubscriptions()).thenReturn(successful(subscriptionData))

      val result = underTest.fetchAllAPISubscriptions()(request)

      status(result) shouldBe OK
    }

    "succeed with a 200 (ok) when no subscriptions are found for any application" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptions()).thenReturn(successful(List()))

      val result = underTest.fetchAllAPISubscriptions()(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptions()).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchAllAPISubscriptions()(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  "notStrideUserDeleteApplication" should {
    val application             = standardApp.inSandbox()
    val applicationId           = application.id
    val gatekeeperUserId        = "big.boss.gatekeeper"
    val requestedByEmailAddress = "admin@example.com".toLaxEmail
    val deleteRequest           = DeleteApplicationRequest(gatekeeperUserId, requestedByEmailAddress)

    "succeed when a sandbox application is successfully deleted" in new Setup with SandboxAuthSetup {
      ApplicationServiceMock.Fetch.thenReturn(application)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(ApplicationServiceMock.aMock).deleteApplication(eqTo(applicationId), eqTo(None), *)(*)
    }

    "succeed when a principal application is in TESTING state is deleted" in new Setup with ProductionAuthSetup {
      val inTesting = standardApp.withState(appStateTesting)

      val inTestingId = application.id

      ApplicationServiceMock.Fetch.thenReturnFor(inTestingId)(inTesting)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(inTestingId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(ApplicationServiceMock.aMock).deleteApplication(eqTo(inTestingId), eqTo(None), *)(*)
    }

    "succeed when a principal application is in PENDING_GATEKEEPER_APPROVAL state is deleted" in new Setup with ProductionAuthSetup {
      val inPending   = standardApp.withState(appStatePendingGatekeeperApproval)
      val inPendingId = application.id

      ApplicationServiceMock.Fetch.thenReturnFor(inPendingId)(inPending)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(inPendingId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(ApplicationServiceMock.aMock).deleteApplication(eqTo(inPendingId), eqTo(None), *)(*)
    }

    "succeed when a principal application is in PENDING_REQUESTER_VERIFICATION state is deleted" in new Setup with ProductionAuthSetup {
      val inPending   = standardApp.withState(appStatePendingRIVerification)
      val inPendingId = application.id

      ApplicationServiceMock.Fetch.thenReturnFor(inPendingId)(inPending)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(inPendingId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(ApplicationServiceMock.aMock).deleteApplication(eqTo(inPendingId), eqTo(None), *)(*)
    }

    "fail when a principal application is in PRODUCTION state is deleted" in new Setup with ProductionAuthSetup {
      val inProd   = standardApp
      val inProdId = application.id

      ApplicationServiceMock.Fetch.thenReturnFor(inProdId)(inProd)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(inProdId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe BAD_REQUEST
      verify(ApplicationServiceMock.aMock, never).deleteApplication(*[ApplicationId], *, *)(*)
    }

    "fail with a bad request error when a production application is requested to be deleted and authorisation key is missing" in new Setup with ProductionAuthSetup {
      ApplicationServiceMock.Fetch.thenReturn(application)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe BAD_REQUEST
      verify(ApplicationServiceMock.aMock, never).deleteApplication(eqTo(applicationId), eqTo(None), *)(*)
    }

    "fail with a bad request when a production application is requested to be deleted and auth key is invalid" in new Setup with ProductionAuthSetup {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      ApplicationServiceMock.Fetch.thenReturn(application)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(applicationId)(
        request
          .withBody(Json.toJson(deleteRequest))
          .withHeaders(AUTHORIZATION -> base64Encode(authorisationKey.reverse))
          .asInstanceOf[FakeRequest[AnyContent]]
      )

      status(result) shouldBe BAD_REQUEST
      verify(ApplicationServiceMock.aMock, never).deleteApplication(eqTo(applicationId), eqTo(None), *)(*)
    }

    "succeed when a production application is requested to be deleted and authorisation key is valid" in new Setup with ProductionAuthSetup {
      ApplicationServiceMock.Fetch.thenReturn(application)
      ApplicationServiceMock.DeleteApplication.thenSucceeds()

      val result = underTest.deleteApplication(applicationId)(request
        .withBody(Json.toJson(deleteRequest))
        .withHeaders(AUTHORIZATION -> base64Encode(authorisationKey))
        .asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(ApplicationServiceMock.aMock).deleteApplication(eqTo(applicationId), eqTo(None), *)(*)
    }

    "fail with a 404 error when a nonexistent sandbox application is requested to be deleted" in new Setup with SandboxAuthSetup {
      ApplicationServiceMock.Fetch.thenReturnNothingFor(applicationId)

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NOT_FOUND
      verify(ApplicationServiceMock.aMock, never).deleteApplication(eqTo(applicationId), eqTo(None), *)(*)
    }
  }

  "getAppsForResponsibleIndividualOrAdmin" should {

    "succeed with a 200 (ok) when applications are found for an email" in new Setup {

      ApplicationServiceMock.GetAppsForAdminOrRI.onRequestReturn(LaxEmailAddressData.one)(List(asAppWithCollaborators(storedApp)))

      val result = underTest.getAppsForResponsibleIndividualOrAdmin()(request.withBody(Json.toJson(GetAppsForAdminOrRIRequest(LaxEmailAddressData.one))))

      status(result) shouldBe OK

      ApplicationServiceMock.GetAppsForAdminOrRI.verifyCalledWith(LaxEmailAddressData.one)
    }

    "succeed with a 200 (ok) when no applications are not found for an email" in new Setup {

      ApplicationServiceMock.GetAppsForAdminOrRI.onRequestReturn(LaxEmailAddressData.one)(List.empty)

      val result = underTest.getAppsForResponsibleIndividualOrAdmin()(request.withBody(Json.toJson(GetAppsForAdminOrRIRequest(LaxEmailAddressData.one))))

      status(result) shouldBe OK

      ApplicationServiceMock.GetAppsForAdminOrRI.verifyCalledWith(LaxEmailAddressData.one)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      ApplicationServiceMock.GetAppsForAdminOrRI.thenThrowFor(LaxEmailAddressData.one)(new RuntimeException("Expected test failure"))

      val result = underTest.getAppsForResponsibleIndividualOrAdmin()(request.withBody(Json.toJson(GetAppsForAdminOrRIRequest(LaxEmailAddressData.one))))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }
}
