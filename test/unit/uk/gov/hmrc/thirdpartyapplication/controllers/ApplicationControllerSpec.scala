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
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.apache.http.HttpStatus._
import org.joda.time.DateTime
import org.mockito.BDDMockito.given
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import play.api.test.FakeRequest
import play.mvc.Http.HeaderNames
import uk.gov.hmrc.auth.core.{Enrolment, SessionRecordNotFound}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.controllers._
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.SILVER
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationResponse, InvalidIpWhitelistException, _}
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, CredentialService, GatekeeperService, SubscriptionService}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.time.DateTimeUtils
import unit.uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

class ApplicationControllerSpec extends AsyncHmrcSpec with GuiceOneAppPerSuite
  with ApplicationStateUtil with TableDrivenPropertyChecks {

  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = fakeApplication().materializer

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    def canDeleteApplications() = true
    def enabled() = true

    val mockGatekeeperService: GatekeeperService = mock[GatekeeperService]
    val mockEnrolment: Enrolment = mock[Enrolment]
    val mockCredentialService: CredentialService = mock[CredentialService]
    val mockApplicationService: ApplicationService = mock[ApplicationService](withSettings.lenient())
    val mockAuthConnector: AuthConnector = mock[AuthConnector](withSettings.lenient())
    val mockSubscriptionService: SubscriptionService = mock[SubscriptionService]

    val mockAuthConfig: AuthConfig = mock[AuthConfig](withSettings.lenient())
    when(mockAuthConfig.enabled).thenReturn(enabled())
    when(mockAuthConfig.userRole).thenReturn("USER")
    when(mockAuthConfig.superUserRole).thenReturn("SUPER")
    when(mockAuthConfig.adminRole).thenReturn("ADMIN")
    when(mockAuthConfig.canDeleteApplications).thenReturn(canDeleteApplications())

    val applicationTtlInSecs = 1234
    val subscriptionTtlInSecs = 4321
    val config = ApplicationControllerConfig(applicationTtlInSecs, subscriptionTtlInSecs)

    val underTest = new ApplicationController(
      mockApplicationService,
      mockAuthConnector,
      mockCredentialService,
      mockSubscriptionService,
      config,
      mockAuthConfig,
      mockGatekeeperService)
  }


  trait CannotDeleteApplications extends Setup{
    override def canDeleteApplications() = false
  }

  trait NotStrideAuthConfig extends Setup{
    override def enabled() = false
  }

  trait PrivilegedAndRopcSetup extends Setup {

    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: UUID, testBlock: => Unit): Unit = {
      givenUserIsAuthenticated(underTest)

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = true, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: UUID, testBlock: => Unit): Unit = {
      givenUserIsNotAuthenticated(underTest)

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = false, testBlock)
    }

    private def testWithPrivilegedAndRopc(applicationId: UUID, gatekeeperLoggedIn: Boolean, testBlock: => Unit): Unit = {
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

  val credentialServiceResponseToken =
    EnvironmentTokenResponse("111", "222", Seq(ClientSecret("333", "333")))
  val controllerResponseTokens = ApplicationTokensResponse(
    credentialServiceResponseToken,
    EnvironmentTokenResponse("", "", Seq()))

  val collaborators: Set[Collaborator] = Set(
    Collaborator("admin@example.com", ADMINISTRATOR),
    Collaborator("dev@example.com", DEVELOPER))

  private val standardAccess = Standard(Seq("http://example.com/redirect"), Some("http://example.com/terms"), Some("http://example.com/privacy"))
  private val privilegedAccess = Privileged(scopes = Set("scope1"))
  private val ropcAccess = Ropc()

  "hc" should {

    "take the X-email-address and X-name fields from the incoming headers" in new Setup {
      val req: FakeRequest[AnyContentAsEmpty.type] = request.withHeaders(
        LOGGED_IN_USER_NAME_HEADER -> "John Smith",
        LOGGED_IN_USER_EMAIL_HEADER -> "test@example.com",
        X_REQUEST_ID_HEADER -> "requestId"
      )

      underTest.hc(req).headers should contain(LOGGED_IN_USER_NAME_HEADER -> "John Smith")
      underTest.hc(req).headers should contain(LOGGED_IN_USER_EMAIL_HEADER -> "test@example.com")
      underTest.hc(req).headers should contain(X_REQUEST_ID_HEADER -> "requestId")
    }

    "contain each header if only one exists" in new Setup {
      val nameHeader: (String, String) = LOGGED_IN_USER_NAME_HEADER -> "John Smith"
      val emailHeader: (String, String) = LOGGED_IN_USER_EMAIL_HEADER -> "test@example.com"

      underTest.hc(request.withHeaders(nameHeader)).headers should contain(nameHeader)
      underTest.hc(request.withHeaders(emailHeader)).headers should contain(emailHeader)
    }
  }

  "Create" should {
    val standardApplicationRequest = aCreateApplicationRequest(standardAccess)
    val privilegedApplicationRequest = aCreateApplicationRequest(privilegedAccess)
    val ropcApplicationRequest = aCreateApplicationRequest(ropcAccess)

    val standardApplicationResponse = CreateApplicationResponse(aNewApplicationResponse())
    val totp = TotpSecrets("pTOTP", "sTOTP")
    val privilegedApplicationResponse = CreateApplicationResponse(aNewApplicationResponse(privilegedAccess), Some(totp))
    val ropcApplicationResponse = CreateApplicationResponse(aNewApplicationResponse(ropcAccess))

    "succeed with a 201 (Created) for a valid Standard application request when service responds successfully" in new Setup {

      when(underTest.applicationService.create(eqTo(standardApplicationRequest))(*)).thenReturn(successful(standardApplicationResponse))

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequest)))

      status(result) shouldBe SC_CREATED
      verify(underTest.applicationService).create(eqTo(standardApplicationRequest))(*)
    }

    "succeed with a 201 (Created) for a valid Privileged application request when gatekeeper is logged in and service responds successfully" in new Setup {

      givenUserIsAuthenticated(underTest)
      when(underTest.applicationService.create(eqTo(privilegedApplicationRequest))(*)).thenReturn(successful(privilegedApplicationResponse))

      val result = underTest.create()(request.withBody(Json.toJson(privilegedApplicationRequest)))

      (contentAsJson(result) \ "totp").as[TotpSecrets] shouldBe totp
      status(result) shouldBe SC_CREATED
      verify(underTest.applicationService).create(eqTo(privilegedApplicationRequest))(*)
    }

    "succeed with a 201 (Created) for a valid ROPC application request when gatekeeper is logged in and service responds successfully" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(underTest.applicationService.create(eqTo(ropcApplicationRequest))(*)).thenReturn(successful(ropcApplicationResponse))

      val result = underTest.create()(request.withBody(Json.toJson(ropcApplicationRequest)))

      status(result) shouldBe SC_CREATED
      verify(underTest.applicationService).create(eqTo(ropcApplicationRequest))(*)
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

      status(result) shouldBe SC_CONFLICT
      contentAsJson(result) shouldBe JsErrorResponse(APPLICATION_ALREADY_EXISTS, "Application already exists with name: appName")
    }

    "fail with a 422 (unprocessable entity) when unexpected json is provided" in new Setup {

      val body = """{ "json": "invalid" }"""

      val result = underTest.create()(request.withBody(Json.parse(body)))

      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
    }

    "fail with a 422 (unprocessable entity) when duplicate email is provided" in new Setup {

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
           |{"emailAddress": "admin@example.com","role": "ADMINISTRATOR"},
           |{"emailAddress": "ADMIN@example.com","role": "ADMINISTRATOR"}
           |]
           |}""".stripMargin.replaceAll("\n", "")

      val result = underTest.create()(request.withBody(Json.parse(body)))

      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
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
          "collaborators": [{"emailAddress": "admin@example.com","role": "ADMINISTRATOR"}]
          }"""

      val result = underTest.create()(request.withBody(Json.parse(createApplicationRequestJson)))

      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
      (contentAsJson(result) \ "message").as[String] shouldBe "requirement failed: maximum number of redirect URIs exceeded"
    }

    "fail with a 422 (unprocessable entity) when incomplete json is provided" in new Setup {

      val body = """{ "name": "myapp" }"""

      val result = underTest.create()(request.withBody(Json.parse(body)))

      status(result) shouldBe SC_UNPROCESSABLE_ENTITY

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
           |"role": "ADMINISTRATOR"
           |},
           |{
           |"emailAddress": "dev@example.com",
           |"role": "developer"
           |}]
           |}""".stripMargin.replaceAll("\n", "")

      val result = underTest.create()(request.withBody(Json.parse(body)))

      val expected: String =
        s"""{
           |"code": "INVALID_REQUEST_PAYLOAD",
           |"message": "Enumeration expected of type: 'Role$$', but it does not contain 'developer'"
           |}""".stripMargin.replaceAll("\n", "")


      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
      contentAsJson(result) shouldBe Json.toJson(Json.parse(expected))
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      when(underTest.applicationService.create(eqTo(standardApplicationRequest))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.create()(request.withBody(Json.toJson(standardApplicationRequest)))

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }
  }

  "Update" should {
    val standardApplicationRequest = anUpdateApplicationRequest(standardAccess)
    val privilegedApplicationRequest = anUpdateApplicationRequest(privilegedAccess)
    val id = UUID.randomUUID()

    "fail with a 404 (not found) when a valid Privileged application and gatekeeper is not logged in" in new Setup {

      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.none)

      val result = underTest.update(id)(request.withBody(Json.toJson(privilegedApplicationRequest)))

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 404 (not found) when id is provided but no application exists for that id" in new Setup {

      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.none)

      val result = underTest.update(id)(request.withBody(Json.toJson(standardApplicationRequest)))

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 422 (unprocessable entity) when request exceeds maximum number of redirect URIs" in new Setup {
      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val updateApplicationRequestJson: String =
        s"""{
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

      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
      (contentAsJson(result) \ "message").as[String] shouldBe "requirement failed: maximum number of redirect URIs exceeded"
    }

  }

  "update approval" should {
    val termsOfUseAgreement = TermsOfUseAgreement("test@example.com", new DateTime(), "1.0")
    val checkInformation = CheckInformation(
      contactDetails = Some(ContactDetails("Tester", "test@example.com", "12345677890")), termsOfUseAgreements = Seq(termsOfUseAgreement))
    val id = UUID.randomUUID()

    "fail with a 404 (not found) when id is provided but no application exists for that id" in new Setup {

      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.none)

      val result = underTest.updateCheck(id)(request.withBody(Json.toJson(checkInformation)))

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "sucessfully update approval information for applicaton" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.updateCheck(eqTo(id), eqTo(checkInformation))).thenReturn(successful(aNewApplicationResponse()))

      val jsonBody: JsValue = Json.toJson(checkInformation)
      val result = underTest.updateCheck(id)(request.withBody(jsonBody))

      status(result) shouldBe SC_OK
    }
  }

  "fetch application" should {
    val applicationId = UUID.randomUUID()

    "succeed with a 200 (ok) if the application exists for the given id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val result = underTest.fetch(applicationId)(request)

      status(result) shouldBe SC_OK
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.fetch(applicationId)(request)

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.liftF(failed(new RuntimeException("Expected test failure"))))

      val result = underTest.fetch(applicationId)(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }
  }

  private def verifyErrorResult(result: Future[Result], statusCode: Int, errorCode: ErrorCode): Unit = {
    status(result) shouldBe statusCode
    (contentAsJson(result) \ "code").as[String] shouldBe errorCode.toString
  }

  "fetch credentials" should {
    val applicationId = UUID.randomUUID()

    "succeed with a 200 (ok) when the application exists for the given id" in new Setup {
      when(mockCredentialService.fetchCredentials(applicationId)).thenReturn(successful(Some(credentialServiceResponseToken)))

      val result = underTest.fetchCredentials(applicationId)(request)

      status(result) shouldBe SC_OK
      contentAsJson(result) shouldBe Json.toJson(controllerResponseTokens)
    }

    "fail with a 404 (not found) when no application exists for the given id" in new Setup {
      when(mockCredentialService.fetchCredentials(applicationId)).thenReturn(successful(None))

      val result = underTest.fetchCredentials(applicationId)(request)

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockCredentialService.fetchCredentials(applicationId)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchCredentials(applicationId)(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "fetch WSO2 credentials" should {
    val clientId = "productionClientId"

    "succeed with a 200 (ok) if the application exists for the given id" in new Setup {
      val wso2Credentials = Wso2Credentials(clientId, "accessToken", "wso2Secret")
      when(mockCredentialService.fetchWso2Credentials(clientId)).thenReturn(successful(Some(wso2Credentials)))

      val result = underTest.fetchWso2Credentials(clientId)(request)

      status(result) shouldBe SC_OK
      contentAsJson(result) shouldBe Json.toJson(wso2Credentials)
    }

    "fail with a 404 (not found) if no application exists for the given client id" in new Setup {
      when(mockCredentialService.fetchWso2Credentials(clientId)).thenReturn(successful(None))

      val result = underTest.fetchWso2Credentials(clientId)(request)

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockCredentialService.fetchWso2Credentials(clientId)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchWso2Credentials(clientId)(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "add collaborators" should {
    val applicationId = UUID.randomUUID()
    val admin = "admin@example.com"
    val email = "test@example.com"
    val role = DEVELOPER
    val isRegistered = false
    val adminsToEmail = Set.empty[String]
    val addCollaboratorRequest = AddCollaboratorRequest(admin, Collaborator(email, role), isRegistered, adminsToEmail)
    val payload = s"""{"adminEmail":"$admin", "collaborator":{"emailAddress":"$email", "role":"$role"}, "isRegistered": $isRegistered, "adminsToEmail": []}"""
    val addRequest: FakeRequest[_] => FakeRequest[JsValue] = request => request.withBody(Json.parse(payload))

    "succeed with a 200 (ok) for a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      val response = AddCollaboratorResponse(registeredUser = true)
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*)).thenReturn(successful(response))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      status(result) shouldBe SC_OK
      contentAsJson(result) shouldBe Json.toJson(response)
    }

    "succeed with a 200 (ok) for a PRIVILEGED or ROPC application and the gatekeeper is logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        val response = AddCollaboratorResponse(registeredUser = true)
        when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*)).thenReturn(successful(response))

        givenUserIsAuthenticated(underTest)

        val result = underTest.addCollaborator(applicationId)(addRequest(request))

        status(result) shouldBe SC_OK
        contentAsJson(result) shouldBe Json.toJson(response)
      })
    }

    "fail with a 401 (unauthorized) for a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        val response = AddCollaboratorResponse(registeredUser = true)
        when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*)).thenReturn(successful(response))

        assertThrows[SessionRecordNotFound](await(underTest.addCollaborator(applicationId)(addRequest(request))))
      })
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 422 (unprocessable) if role is invalid" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val result = underTest.addCollaborator(applicationId)(request.withBody(Json.obj("emailAddress" -> s"$email", "role" -> "invalid")))

      verifyErrorResult(result, SC_UNPROCESSABLE_ENTITY, ErrorCode.INVALID_REQUEST_PAYLOAD)
    }

    "fail with a 409 (conflict) if email already registered with different role" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*))
        .thenReturn(failed(new UserAlreadyExists))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      verifyErrorResult(result, SC_CONFLICT, ErrorCode.USER_ALREADY_EXISTS)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "remove collaborator" should {
    val applicationId = UUID.randomUUID()
    val admin = "admin@example.com"
    val collaborator = "dev@example.com"
    val adminsToEmailSet = Set.empty[String]
    val adminsToEmailString = ""

    "succeed with a 204 (No Content) for a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(admin), eqTo(adminsToEmailSet))(*))
        .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR))))

      val result = underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmailString)(request)

      status(result) shouldBe SC_NO_CONTENT
    }

    "succeed with a 204 (No Content) for a PRIVILEGED or ROPC application when the Gatekeeper is logged in" in new PrivilegedAndRopcSetup {

      givenUserIsAuthenticated(underTest)

      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(underTest.applicationService.deleteCollaborator(
          eqTo(applicationId), eqTo(collaborator), eqTo(admin), eqTo(adminsToEmailSet))(*))
          .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR))))

        val result = underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmailString)(request)

        status(result) shouldBe SC_NO_CONTENT
      })
    }

    "fail with a 401 (Unauthorized) for a PRIVILEGED or ROPC application when the Gatekeeper is not logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        when(underTest.applicationService.deleteCollaborator(
          eqTo(applicationId), eqTo(collaborator), eqTo(admin), eqTo(adminsToEmailSet))(*))
          .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR))))

        assertThrows[SessionRecordNotFound](await(underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmailString)(request)))
      })
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmailString)(request)

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 403 (forbidden) if deleting the only admin" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(admin), eqTo(adminsToEmailSet))(*))
        .thenReturn(failed(new ApplicationNeedsAdmin))

      val result = underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmailString)(request)

      verifyErrorResult(result, SC_FORBIDDEN, ErrorCode.APPLICATION_NEEDS_ADMIN)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(admin), eqTo(adminsToEmailSet))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmailString)(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "add client secret" should {
    val applicationId = UUID.randomUUID()
    val environmentTokenResponse = EnvironmentTokenResponse("clientId", "token", Seq(aSecret("secret1"), aSecret("secret2")))
    val applicationTokensResponse = ApplicationTokensResponse(
      environmentTokenResponse,
      EnvironmentTokenResponse("", "", Seq()))
    val secretRequest = ClientSecretRequest("request")

    "succeed with a 200 (ok) when the application exists for the given id" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(successful(environmentTokenResponse))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe SC_OK
        contentAsJson(result) shouldBe Json.toJson(applicationTokensResponse)
      })
    }

    "succeed with a 200 (ok) when request originates from outside gatekeeper" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(successful(environmentTokenResponse))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe SC_OK
        contentAsJson(result) shouldBe Json.toJson(applicationTokensResponse)
      })
    }

    "fail with a 403 (Forbidden) when the environment has already the maximum number of secrets set" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(failed(new ClientSecretsLimitExceeded))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        verifyErrorResult(result, SC_FORBIDDEN, ErrorCode.CLIENT_SECRET_LIMIT_EXCEEDED)
      })
    }

    "fail with a 404 (not found) when no application exists for the given id" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(failed(new NotFoundException("application not found")))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
      })
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(failed(new RuntimeException))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe SC_INTERNAL_SERVER_ERROR
      })
    }

  }

  "delete client secret" should {

    val applicationId = UUID.randomUUID()
    val secrets = "ccc"
    val splitSecrets = secrets.split(",").toSeq
    val secretRequest = DeleteClientSecretsRequest(splitSecrets)
    val environmentTokenResponse = EnvironmentTokenResponse("aaa", "bbb", Seq())

    "succeed with a 204 for a STANDARD application" in new Setup {

      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockCredentialService.deleteClientSecrets(eqTo(applicationId), eqTo(splitSecrets))(*))
        .thenReturn(successful(environmentTokenResponse))

      val result = underTest.deleteClientSecrets(applicationId)(request.withBody(Json.toJson(secretRequest)))

      status(result) shouldBe SC_NO_CONTENT
    }

    "succeed with a 204 (No Content) for a PRIVILEGED or ROPC application when the Gatekeeper is logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.deleteClientSecrets(eqTo(applicationId), eqTo(splitSecrets))(*))
          .thenReturn(successful(environmentTokenResponse))

        val result = underTest.deleteClientSecrets(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe SC_NO_CONTENT
      })
    }

    "succeed with a 204 for a PRIVILEGED or ROPC application when the request originates outside gatekeeper" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        when(mockCredentialService.deleteClientSecrets(eqTo(applicationId), eqTo(splitSecrets))(*))
          .thenReturn(successful(environmentTokenResponse))

        val result = underTest.deleteClientSecrets(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe SC_NO_CONTENT
      })
    }
  }

  private def aSecret(secret: String): ClientSecret = {
    ClientSecret(secret, secret)
  }

  "validate credentials" should {
    val validation = ValidationRequest("clientId", "clientSecret")
    val payload = s"""{"clientId":"${validation.clientId}", "clientSecret":"${validation.clientSecret}"}"""

    "succeed with a 200 (ok) if the credentials are valid for an application" in new Setup {

      when(mockCredentialService.validateCredentials(validation)).thenReturn(successful(Some(PRODUCTION)))

      val result = underTest.validateCredentials(request.withBody(Json.parse(payload)))

      status(result) shouldBe SC_OK
      contentAsJson(result) shouldBe Json.obj("environment" -> PRODUCTION.toString)
    }

    "fail with a 401 if credentials are invalid for an application" in new Setup {

      when(mockCredentialService.validateCredentials(validation)).thenReturn(successful(None))

      val result = underTest.validateCredentials(request.withBody(Json.parse(payload)))

      verifyErrorResult(result, SC_UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      when(mockCredentialService.validateCredentials(validation)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result =underTest.validateCredentials(request.withBody(Json.parse(payload)))

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "validate name" should {
    "Allow a valid app" in new Setup {

      val applicationName = "my valid app name"
      val appId = UUID.randomUUID()
      val payload = s"""{"applicationName":"${applicationName}", "environment":"PRODUCTION", "selfApplicationId" : "${appId.toString}" }"""

      when(mockApplicationService.validateApplicationName(*, *)(*))
        .thenReturn(successful(Valid))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe SC_OK

      contentAsJson(result) shouldBe Json.obj()

      verify(mockApplicationService).validateApplicationName(eqTo(applicationName), eqTo(Some(appId)))(*)
    }

    "Allow a valid app with an optional selfApplicationId" in new Setup {
      val applicationName = "my valid app name"
      val appId = UUID.randomUUID()
      val payload = s"""{"applicationName":"${applicationName}", "environment":"PRODUCTION"}"""

      when(mockApplicationService.validateApplicationName(*, *)(*))
        .thenReturn(successful(Valid))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe SC_OK

      contentAsJson(result) shouldBe Json.obj()

      verify(mockApplicationService).validateApplicationName(*, eqTo(None))(*)
    }

    "Reject an app name as it contains a block bit of text" in new Setup {
      val applicationName = "my invalid HMRC app name"
      val payload = s"""{"applicationName":"${applicationName}"}"""

      when(mockApplicationService.validateApplicationName(*, *)(*))
        .thenReturn(successful(Invalid.invalidName))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe SC_OK

      contentAsJson(result) shouldBe Json.obj("errors" -> Json.obj("invalidName" -> true, "duplicateName" -> false))

      verify(mockApplicationService).validateApplicationName(eqTo(applicationName),eqTo(None))(*)
    }

    "Reject an app name as it is a duplicate name" in new Setup {
      val applicationName = "my duplicate app name"
      val payload = s"""{"applicationName":"${applicationName}"}"""

      when(mockApplicationService.validateApplicationName(*,*)(*))
        .thenReturn(successful(Invalid.duplicateName))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe SC_OK

      contentAsJson(result) shouldBe Json.obj("errors" -> Json.obj("invalidName" -> false, "duplicateName" -> true))

      verify(mockApplicationService).validateApplicationName(eqTo(applicationName),eqTo(None))(*)
    }
  }

  "query dispatcher" should {
    val clientId = "A123XC"
    val serverToken = "b3c83934c02df8b111e7f9f8700000"

    trait LastAccessedSetup extends Setup {
      val lastAccessTime: DateTime = DateTime.now().minusDays(10) //scalastyle:ignore magic.number
      val updatedLastAccessTime: DateTime = DateTime.now()
      val applicationId: UUID = UUID.randomUUID()

      val applicationResponse: ApplicationResponse =
        aNewApplicationResponse().copy(id = applicationId, lastAccess = Some(lastAccessTime))
      val updatedApplicationResponse: ExtendedApplicationResponse = extendedApplicationResponseFromApplicationResponse(applicationResponse).copy(lastAccess = Some(updatedLastAccessTime))

      when(underTest.applicationService.recordApplicationUsage(applicationId)).thenReturn(Future(updatedApplicationResponse))
    }

    def validateResult(result: Future[Result], expectedResponseCode: Int,
                       expectedCacheControlHeader: Option[String],
                       expectedVaryHeader: Option[String]) = {
      status(result) shouldBe expectedResponseCode
      headers(result).get(HeaderNames.CACHE_CONTROL) shouldBe expectedCacheControlHeader
      headers(result).get(HeaderNames.VARY) shouldBe expectedVaryHeader
    }

    "retrieve by client id" in new Setup {
      when(underTest.applicationService.fetchByClientId(clientId)).thenReturn(Future(Some(aNewApplicationResponse())))

      private val result =underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=$clientId"))

      validateResult(result, SC_OK, Some(s"max-age=$applicationTtlInSecs"), None)
    }

    "retrieve by server token" in new Setup {
      when(underTest.applicationService.fetchByServerToken(serverToken)).thenReturn(Future(Some(aNewApplicationResponse())))

      private val scenarios =
        Table(
          ("serverTokenHeader", "expectedVaryHeader"),
          ("X-Server-Token", "X-server-token"),
          ("X-SERVER-TOKEN", "X-server-token"))

      forAll(scenarios) { (serverTokenHeader, expectedVaryHeader) =>
        val result =underTest.queryDispatcher()(request.withHeaders(serverTokenHeader -> serverToken))

        validateResult(result, SC_OK, Some(s"max-age=$applicationTtlInSecs"), Some(expectedVaryHeader))
      }
    }

    "retrieve all" in new Setup {
      when(underTest.applicationService.fetchAll()).thenReturn(Future(Seq(aNewApplicationResponse(), aNewApplicationResponse())))

      private val result =underTest.queryDispatcher()(FakeRequest())

      validateResult(result, SC_OK, None, None)
      contentAsJson(result).as[Seq[JsValue]] should have size 2
    }

    "retrieve when no subscriptions" in new Setup {
      when(underTest.applicationService.fetchAllWithNoSubscriptions()).thenReturn(Future(Seq(aNewApplicationResponse())))

      private val result =underTest.queryDispatcher()(FakeRequest("GET", s"?noSubscriptions=true"))

      validateResult(result, SC_OK, None, None)
    }

    "fail with a 500 (internal server error) when an exception is thrown from fetchAll" in new Setup {
      when(underTest.applicationService.fetchAll()).thenReturn(failed(new RuntimeException("Expected test exception")))

      private val result =underTest.queryDispatcher()(FakeRequest())

      validateResult(result, SC_INTERNAL_SERVER_ERROR, None, None)
    }

    "fail with a 500 (internal server error) when a clientId is supplied" in new Setup {
      when(underTest.applicationService.fetchByClientId(clientId)).thenReturn(failed(new RuntimeException("Expected test exception")))

      private val result =underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=$clientId"))

      validateResult(result, SC_INTERNAL_SERVER_ERROR, None, None)
    }

    "update last accessed time when an API gateway retrieves Application by Server Token" in new LastAccessedSetup {
      when(underTest.applicationService.fetchByServerToken(serverToken)).thenReturn(Future(Some(applicationResponse)))
      val scenarios =
        Table(
          ("headers", "expectedLastAccessTime"),
          (Seq(SERVER_TOKEN_HEADER -> serverToken, USER_AGENT -> "APIPlatformAuthorizer"), updatedLastAccessTime.getMillis),
          (Seq(SERVER_TOKEN_HEADER -> serverToken, USER_AGENT -> "wso2-gateway-customizations"), updatedLastAccessTime.getMillis),
          (Seq(SERVER_TOKEN_HEADER -> serverToken, USER_AGENT -> "foobar"), lastAccessTime.getMillis),
          (Seq(SERVER_TOKEN_HEADER -> serverToken), lastAccessTime.getMillis)
        )

      forAll(scenarios) { (headers, expectedLastAccessTime) =>
        val result =
          underTest.queryDispatcher()(request.withHeaders(headers: _*))

        validateResult(result, SC_OK, Some(s"max-age=$applicationTtlInSecs"), Some(SERVER_TOKEN_HEADER))
        (contentAsJson(result) \ "lastAccess").as[Long] shouldBe expectedLastAccessTime
      }
    }

    "update last accessed time when an API gateway retrieves Application by Client Id" in new LastAccessedSetup {
      when(underTest.applicationService.fetchByClientId(clientId)).thenReturn(Future(Some(applicationResponse)))

      val scenarios =
        Table(
          ("headers", "expectedLastAccessTime"),
          (Seq(USER_AGENT -> "APIPlatformAuthorizer"), updatedLastAccessTime.getMillis),
          (Seq(USER_AGENT -> "wso2-gateway-customizations"), updatedLastAccessTime.getMillis),
          (Seq(USER_AGENT -> "foobar"), lastAccessTime.getMillis),
          (Seq(), lastAccessTime.getMillis)
        )

      forAll(scenarios) { (headers, expectedLastAccessTime) =>
        val result =
          underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=$clientId").withHeaders(headers: _*))

        validateResult(result, SC_OK, Some(s"max-age=$applicationTtlInSecs"), None)
        (contentAsJson(result) \ "lastAccess").as[Long] shouldBe expectedLastAccessTime
      }
    }
  }

  "fetchAllForCollaborator" should {

    val emailAddress = "dev@example.com"
    val environment = "PRODUCTION"
    val queryRequest = FakeRequest("GET", s"?emailAddress=$emailAddress")

    "succeed with a 200 (ok) when applications are found for the collaborator" in new Setup {
      val standardApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Standard())
      val privilegedApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Privileged())
      val ropcApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Ropc())

      when(underTest.applicationService.fetchAllForCollaborator(emailAddress))
        .thenReturn(successful(Seq(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)))

      status(underTest.queryDispatcher()(queryRequest)) shouldBe SC_OK
    }

    "succeed with a 200 (ok) when applications are found for the collaborator and the environment" in new Setup {
      val queryRequestWithEnvironment = FakeRequest("GET", s"?emailAddress=$emailAddress&environment=$environment")
      val standardApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Standard())
      val privilegedApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Privileged())
      val ropcApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Ropc())

      when(underTest.applicationService.fetchAllForCollaboratorAndEnvironment(emailAddress, environment))
        .thenReturn(successful(Seq(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)))

      status(underTest.queryDispatcher()(queryRequestWithEnvironment)) shouldBe SC_OK
    }

    "succeed with a 200 (ok) when no applications are found for the collaborator" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(emailAddress)).thenReturn(successful(Nil))

      val result = underTest.queryDispatcher()(queryRequest)

      status(result) shouldBe SC_OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(emailAddress)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.queryDispatcher()(queryRequest)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "fetchAllBySubscription" when {
    val subscribesTo = "an-api"

    "not given a version" should {
      val queryRequest = FakeRequest("GET", s"?subscribesTo=$subscribesTo")

      "succeed with a 200 (ok) when applications are found" in new Setup {
        val standardApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Standard())
        val privilegedApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Privileged())
        val ropcApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Ropc())
        val response: Seq[ApplicationResponse] = Seq(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)

        when(underTest.applicationService.fetchAllBySubscription(subscribesTo)).thenReturn(successful(response))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe SC_OK

        contentAsJson(result) shouldBe Json.toJson(response)
      }

      "succeed with a 200 (ok) when no applications are found" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(subscribesTo)).thenReturn(successful(Seq()))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe SC_OK

        contentAsString(result) shouldBe "[]"
      }

      "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(subscribesTo)).thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe SC_INTERNAL_SERVER_ERROR
      }
    }

    "given a version" should {
      val version = "1.0"
      val queryRequest = FakeRequest("GET", s"?subscribesTo=$subscribesTo&version=$version")
      val apiIdentifier = APIIdentifier(subscribesTo, version)

      "succeed with a 200 (ok) when applications are found" in new Setup {
        val standardApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Standard())
        val privilegedApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Privileged())
        val ropcApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Ropc())
        val response: Seq[ApplicationResponse] = Seq(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)

        when(underTest.applicationService.fetchAllBySubscription(apiIdentifier)).thenReturn(successful(response))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe SC_OK

        contentAsJson(result) shouldBe Json.toJson(response)

      }

      "succeed with a 200 (ok) when no applications are found" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(apiIdentifier)).thenReturn(successful(Seq()))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe SC_OK

        contentAsString(result) shouldBe "[]"
      }

      "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(apiIdentifier)).thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe SC_INTERNAL_SERVER_ERROR
      }
    }
  }

  "isSubscribed" should {

    val applicationId: UUID = UUID.randomUUID()
    val context: String = "context"
    val version: String = "1.0"
    val api = APIIdentifier(context, version)

    "succeed with a 200 (ok) when the application is subscribed to a given API version" in new Setup {

      given(mockSubscriptionService.isSubscribed(applicationId, api)).willReturn(successful(true))

      val result = underTest.isSubscribed(applicationId, context, version)(request)

      status(result) shouldBe SC_OK
      contentAsJson(result) shouldBe Json.toJson(api)
      headers(result).get(HeaderNames.CACHE_CONTROL) shouldBe Some(s"max-age=$subscriptionTtlInSecs")
    }

    "fail with a 404 (not found) when the application is not subscribed to a given API version" in new Setup {

      given(mockSubscriptionService.isSubscribed(applicationId, api)).willReturn(successful(false))

      val result = underTest.isSubscribed(applicationId, context, version)(request)

      status(result) shouldBe SC_NOT_FOUND
      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.SUBSCRIPTION_NOT_FOUND)
      headers(result).get(HeaderNames.CACHE_CONTROL) shouldBe None
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      given(mockSubscriptionService.isSubscribed(applicationId, api)).willReturn(failed(new RuntimeException("something went wrong")))

      val result = underTest.isSubscribed(applicationId, context, version)(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
      headers(result).get(HeaderNames.CACHE_CONTROL) shouldBe None
    }
  }

  "fetchAllSubscriptions by ID" should {

    val applicationId = UUID.randomUUID()
    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId))(*))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe SC_NOT_FOUND
    }

    "succeed with a 200 (ok) when subscriptions are found for the application" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId))(*))
        .thenReturn(successful(Seq(anAPISubscription())))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe SC_OK
    }

    "succeed with a 200 (ok) when no subscriptions are found for the application" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId))(*)).thenReturn(successful(Seq()))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe SC_OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "fetchAllSubscriptions" should {

    "succeed with a 200 (ok) when subscriptions are found for the application" in new Setup {

      val subscriptionData = List(aSubcriptionData(), aSubcriptionData())

      when(mockSubscriptionService.fetchAllSubscriptions()).thenReturn(successful(subscriptionData))

      val result = underTest.fetchAllAPISubscriptions()(request)

      status(result) shouldBe SC_OK
    }

    "succeed with a 200 (ok) when no subscriptions are found for any application" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptions()).thenReturn(successful(List()))

      val result = underTest.fetchAllAPISubscriptions()(request)

      status(result) shouldBe SC_OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptions()).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchAllAPISubscriptions()(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }
  }

  "createSubscriptionForApplication" should {
    val applicationId = UUID.randomUUID()
    val body = anAPIJson()

    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "succeed with a 204 (no content) when a subscription is successfully added to a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.createSubscriptionForApplication(eqTo(applicationId), *)(*))
        .thenReturn(successful(HasSucceeded))

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe SC_NO_CONTENT
    }

    "succeed with a 204 (no content) when a subscription is successfully added to a PRIVILEGED or ROPC application and the gatekeeper is logged in" in
      new PrivilegedAndRopcSetup {

        givenUserIsAuthenticated(underTest)

        testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
          when(mockSubscriptionService.createSubscriptionForApplication(eqTo(applicationId), *)(*))
            .thenReturn(successful(HasSucceeded))

          status(underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))) shouldBe SC_NO_CONTENT
        })
      }

    "fail with 401 (Unauthorized) when adding a subscription to a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in
      new PrivilegedAndRopcSetup {

        testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
          assertThrows[SessionRecordNotFound](await(underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))))
        })
      }

    "fail with a 422 (unprocessable entity) when unexpected json is provided" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val body = """{ "json": "invalid" }"""

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.createSubscriptionForApplication(eqTo(applicationId), *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "removeSubscriptionForApplication" should {
    val applicationId = UUID.randomUUID()

    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.removeSubscriptionForApplication(applicationId, "some-context", "1.0")(request)

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "succeed with a 204 (no content) when a subscription is successfully removed from a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.removeSubscriptionForApplication(eqTo(applicationId), *)(*))
        .thenReturn(successful(HasSucceeded))

      val result = underTest.removeSubscriptionForApplication(applicationId, "some-context", "1.0")(request)

      status(result) shouldBe SC_NO_CONTENT
    }

    "succeed with a 204 (no content) when a subscription is successfully removed from a PRIVILEGED or ROPC application and the gatekeeper is logged in" in
      new PrivilegedAndRopcSetup {

        givenUserIsAuthenticated(underTest)

        testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
          when(mockSubscriptionService.removeSubscriptionForApplication(eqTo(applicationId), *)(*))
            .thenReturn(successful(HasSucceeded))

          status(underTest.removeSubscriptionForApplication(applicationId, "some-context", "1.0")(request)) shouldBe SC_NO_CONTENT
        })
      }

    "fail with a 401 (unauthorized) when trying to remove a subscription from a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in
      new PrivilegedAndRopcSetup {
        testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
          assertThrows[SessionRecordNotFound](await(underTest.removeSubscriptionForApplication(applicationId, "some-context", "1.0")(request)))
        })
      }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.removeSubscriptionForApplication(eqTo(applicationId), *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.removeSubscriptionForApplication(applicationId, "some-context", "1.0")(request)

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

  }

  "verifyUplift" should {

    "verify uplift successfully" in new Setup {
      val verificationCode = "aVerificationCode"

      when(underTest.applicationService.verifyUplift(eqTo(verificationCode))(*)).thenReturn(successful(UpliftVerified))

      val result = underTest.verifyUplift(verificationCode)(request)
      status(result) shouldBe SC_NO_CONTENT
    }

    "verify uplift failed" in new Setup {
      val verificationCode = "aVerificationCode"

      when(underTest.applicationService.verifyUplift(eqTo(verificationCode))(*))
        .thenReturn(failed(InvalidUpliftVerificationCode(verificationCode)))

      val result = underTest.verifyUplift(verificationCode)(request)
      status(result) shouldBe SC_BAD_REQUEST
    }
  }

  "requestUplift" should {
    val applicationId = UUID.randomUUID()
    val requestedByEmailAddress = "big.boss@example.com"
    val requestedName = "Application Name"
    val upliftRequest = UpliftRequest(requestedName, requestedByEmailAddress)

    "return updated application if successful" in new Setup {
      val resultUpliftedApplication: ApplicationResponse = aNewApplicationResponse().copy(state = pendingGatekeeperApprovalState(requestedByEmailAddress))

      when(underTest.applicationService.requestUplift(eqTo(applicationId), eqTo(requestedName), eqTo(requestedByEmailAddress))(*))
        .thenReturn(successful(UpliftRequested))

      val result = underTest.requestUplift(applicationId)(request
        .withBody(Json.toJson(upliftRequest)))

      status(result) shouldBe SC_NO_CONTENT
    }

    "return 404 if the application doesn't exist" in new Setup {

      when(underTest.applicationService.requestUplift(eqTo(applicationId), eqTo(requestedName), eqTo(requestedByEmailAddress))(*))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, SC_NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 409 (conflict) when an application already exists for that application name" in new Setup {

      when(underTest.applicationService.requestUplift(eqTo(applicationId), eqTo(requestedName), eqTo(requestedByEmailAddress))(*))
        .thenReturn(failed(ApplicationAlreadyExists("applicationName")))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, SC_CONFLICT, ErrorCode.APPLICATION_ALREADY_EXISTS)
    }

    "fail with 412 (Precondition Failed) when the application is not in the TESTING state" in new Setup {

      when(underTest.applicationService.requestUplift(eqTo(applicationId), eqTo(requestedName), eqTo(requestedByEmailAddress))(*))
        .thenReturn(failed(new InvalidStateTransition(State.PRODUCTION, State.PENDING_GATEKEEPER_APPROVAL, State.TESTING)))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, SC_PRECONDITION_FAILED, ErrorCode.INVALID_STATE_TRANSITION)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.requestUplift(eqTo(applicationId), eqTo(requestedName), eqTo(requestedByEmailAddress))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.requestUplift(applicationId)(request.withBody(Json.toJson(upliftRequest)))

      verifyErrorResult(result, SC_INTERNAL_SERVER_ERROR, ErrorCode.UNKNOWN_ERROR)
    }
  }

  "update rate limit tier" should {

    val uuid: UUID = UUID.randomUUID()
    val invalidUpdateRateLimitTierJson = Json.parse("""{ "foo" : "bar" }""")
    val validUpdateRateLimitTierJson = Json.parse("""{ "rateLimitTier" : "silver" }""")

    "fail with a 422 (unprocessable entity) when request json is invalid" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = underTest.updateRateLimitTier(uuid)(request.withBody(invalidUpdateRateLimitTierJson))

      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
      verify(underTest.applicationService, never).updateRateLimitTier(eqTo(uuid), eqTo(SILVER))(*)
    }

    "fail with a 422 (unprocessable entity) when request json is valid but rate limit tier is an invalid value" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = underTest.updateRateLimitTier(uuid)(request.withBody(Json.parse("""{ "rateLimitTier" : "multicoloured" }""")))
      status(result) shouldBe SC_UNPROCESSABLE_ENTITY
      contentAsJson(result) shouldBe Json.toJson(Json.parse(
        """
         {
         "code": "INVALID_REQUEST_PAYLOAD",
         "message": "'multicoloured' is an invalid rate limit tier"
         }"""
      ))
    }

    "succeed with a 204 (no content) when rate limit tier is successfully added to application" in new Setup {

      givenUserIsAuthenticated(underTest)

      when(underTest.applicationService.updateRateLimitTier(eqTo(uuid), eqTo(SILVER))(*)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.updateRateLimitTier(uuid)(request.withBody(validUpdateRateLimitTierJson))

      status(result) shouldBe SC_NO_CONTENT
      verify(underTest.applicationService).updateRateLimitTier(eqTo(uuid), eqTo(SILVER))(*)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      givenUserIsAuthenticated(underTest)

      when(underTest.applicationService.updateRateLimitTier(eqTo(uuid), eqTo(SILVER))(*))
        .thenReturn(failed(new RuntimeException("Expected test exception")))

      val result = underTest.updateRateLimitTier(uuid)(request.withBody(validUpdateRateLimitTierJson))

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
    }

    "fail with a 401 (Unauthorized) when the request is done without a gatekeeper token" in new Setup {

      givenUserIsNotAuthenticated(underTest)

      when(underTest.applicationService.updateRateLimitTier(eqTo(uuid), eqTo(SILVER))(*)).thenReturn(successful(mock[ApplicationData]))

      assertThrows[SessionRecordNotFound](await(underTest.updateRateLimitTier(uuid)(request.withBody(validUpdateRateLimitTierJson))))
    }
  }

  "update IP whitelist" should {
    "succeed with a 204 (no content) when the IP whitelist is successfully added to the application" in new Setup {
      val applicationId: UUID = UUID.randomUUID()
      val validUpdateIpWhitelistJson: JsValue = Json.parse("""{ "ipWhitelist" : ["192.168.100.0/22", "192.168.104.1/32"] }""")
      when(underTest.applicationService.updateIpWhitelist(eqTo(applicationId), *)(*)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.updateIpWhitelist(applicationId)(request.withBody(validUpdateIpWhitelistJson))

      status(result) shouldBe SC_NO_CONTENT
    }

    "fail when the JSON message is invalid" in new Setup {
      val applicationId: UUID = UUID.randomUUID()
      val invalidUpdateIpWhitelistJson: JsValue = Json.parse("""{ "foo" : ["192.168.100.0/22", "192.168.104.1/32"] }""")
      when(underTest.applicationService.updateIpWhitelist(eqTo(applicationId), *)(*)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.updateIpWhitelist(applicationId)(request.withBody(invalidUpdateIpWhitelistJson))

      verifyErrorResult(result, SC_UNPROCESSABLE_ENTITY, INVALID_REQUEST_PAYLOAD)
    }

    "fail when the IP whitelist is invalid" in new Setup {
      val applicationId: UUID = UUID.randomUUID()
      val invalidUpdateIpWhitelistJson: JsValue = Json.parse("""{ "ipWhitelist" : ["392.168.100.0/22"] }""")
      val errorMessage = "invalid IP whitelist"
      when(underTest.applicationService.updateIpWhitelist(eqTo(applicationId), *)(*))
        .thenReturn(Future.failed(InvalidIpWhitelistException(errorMessage)))

      val result = underTest.updateIpWhitelist(applicationId)(request.withBody(invalidUpdateIpWhitelistJson))

      verifyErrorResult(result, SC_BAD_REQUEST, INVALID_IP_WHITELIST)
    }
  }

  "Search" should {
    "pass an ApplicationSearch object to applicationService" in new Setup {
      val req: FakeRequest[AnyContentAsEmpty.type] =
        FakeRequest("GET", "/applications?apiSubscriptions=ANYSUB&page=1&pageSize=100")
          .withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

      // scalastyle:off magic.number
      when(underTest.applicationService.searchApplications(any[ApplicationSearch]))
        .thenReturn(Future(PaginatedApplicationResponse(applications = Seq.empty, page = 1, pageSize = 100, total = 0, matching = 0)))

      val result = underTest.searchApplications(req)

      status(result) shouldBe SC_OK
    }
  }

  "notStrideUserDeleteApplication" should {
    val application = aNewApplicationResponse(environment = SANDBOX)
    val applicationId = application.id
    val gatekeeperUserId = "big.boss.gatekeeper"
    val requestedByEmailAddress = "admin@example.com"
    val deleteRequest = DeleteApplicationRequest(gatekeeperUserId, requestedByEmailAddress)

    "succeed when a sandbox application is successfully deleted" in new NotStrideAuthConfig {
      when(mockApplicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](application))
      when(mockApplicationService.deleteApplication(*, *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe SC_NO_CONTENT
      verify(mockApplicationService).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }

    "fail with a 400 error when a production application is requested to be deleted" in new CannotDeleteApplications with NotStrideAuthConfig {
      when(mockApplicationService.fetch(*)).thenReturn(OptionT.pure[Future](application))
      when(mockApplicationService.deleteApplication(*, *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe SC_BAD_REQUEST
      verify(mockApplicationService,times(0)).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }

    "fail with a 404 error when a nonexistent sandbox application is requested to be deleted" in new NotStrideAuthConfig {
      when(mockApplicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe SC_NOT_FOUND
      verify(mockApplicationService,times(0)).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }
  }

  "strideUserDeleteApplication" should {
    val applicationId = UUID.randomUUID()
    val gatekeeperUserId = "big.boss.gatekeeper"
    val requestedByEmailAddress = "admin@example.com"
    val deleteRequest = DeleteApplicationRequest(gatekeeperUserId, requestedByEmailAddress)

    "succeed with a 204 (no content) when the application is successfully deleted" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(mockGatekeeperService.deleteApplication(*, *)(*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(applicationId).apply(request
        .withHeaders(AUTHORIZATION -> "foo")
        .withBody(AnyContentAsJson(Json.toJson(deleteRequest))))

      status(result) shouldBe SC_NO_CONTENT
      verify(mockGatekeeperService).deleteApplication(eqTo(applicationId), eqTo(deleteRequest)) (*)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(mockGatekeeperService.deleteApplication(*, *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.deleteApplication(applicationId).apply(request
        .withHeaders(AUTHORIZATION -> "foo")
        .withBody(AnyContentAsJson(Json.toJson(deleteRequest))))

      status(result) shouldBe SC_INTERNAL_SERVER_ERROR
      verify(mockGatekeeperService).deleteApplication(eqTo(applicationId), eqTo(deleteRequest)) (*)
    }
  }

  private def anAPI() = {
    new APIIdentifier("some-context", "1.0")
  }

  private def anAPISubscription() = {
    new ApiSubscription("name", "service-name", "some-context", Seq(VersionSubscription(ApiVersion("1.0", ApiStatus.STABLE, None), subscribed = true)))
  }

  private def aSubcriptionData() = {
    SubscriptionData(anAPI(), Set(UUID.randomUUID(), UUID.randomUUID()))
  }

  private def anAPIJson() = {
    """{ "context" : "some-context", "version" : "1.0" }"""
  }

  private def aNewApplicationResponse(access: Access = standardAccess, environment: Environment = Environment.PRODUCTION) = {
    new ApplicationResponse(
      UUID.randomUUID(),
      "clientId",
      "gatewayId",
      "My Application",
      environment.toString,
      Some("Description"),
      collaborators,
      DateTimeUtils.now,
      Some(DateTimeUtils.now),
      standardAccess.redirectUris,
      standardAccess.termsAndConditionsUrl,
      standardAccess.privacyPolicyUrl,
      access,
      environment = Some(environment)
    )
  }

  private def extendedApplicationResponseFromApplicationResponse(app: ApplicationResponse) = {
    new ExtendedApplicationResponse(
      app.id,
      app.clientId,
      app.gatewayId,
      app.name,
      app.deployedTo,
      app.description,
      app.collaborators,
      app.createdOn,
      app.lastAccess,
      app.redirectUris,
      app.termsAndConditionsUrl,
      app.privacyPolicyUrl,
      app.access,
      app.environment,
      app.state,
      app.rateLimitTier,
      app.checkInformation,
      app.blocked,
      app.ipWhitelist,
      app.trusted,
      UUID.randomUUID().toString,
      Seq.empty
    )
  }

  private def anUpdateApplicationRequest(access: Access) = UpdateApplicationRequest("My Application", access, Some("Description"))

  private def aCreateApplicationRequest(access: Access) = CreateApplicationRequest("My Application", access, Some("Description"),
    Environment.PRODUCTION, Set(Collaborator("admin@example.com", ADMINISTRATOR), Collaborator("dev@example.com", ADMINISTRATOR)))
}
