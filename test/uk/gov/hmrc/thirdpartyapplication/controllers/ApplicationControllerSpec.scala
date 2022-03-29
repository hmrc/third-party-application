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
import org.joda.time.DateTime
import org.scalatest.prop.TableDrivenPropertyChecks
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.FakeRequest
import play.mvc.Http.HeaderNames
import uk.gov.hmrc.auth.core.AuthorisationException
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.helpers.AuthSpecHelpers._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.SILVER
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{TermsOfUseAcceptance, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.services.CredentialService
import uk.gov.hmrc.thirdpartyapplication.services.GatekeeperService
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.time.DateTimeUtils

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.{util => ju}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.failed
import scala.concurrent.Future.successful
import akka.stream.testkit.NoMaterializer
import org.mockito.captor.ArgCaptor
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.apiplatform.modules.upliftlinks.service.UpliftLinkService

class ApplicationControllerSpec
  extends ControllerSpec
  with ApplicationStateUtil 
  with ControllerTestData
  with TableDrivenPropertyChecks
  with ApplicationTestData {

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
    val mockUpliftNamingService: UpliftNamingService = mock[UpliftNamingService]
    val mockUpliftLinkService: UpliftLinkService = mock[UpliftLinkService]

    val mockAuthConfig: AuthConnector.Config = mock[AuthConnector.Config]
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
      mockAuthConfig,
      mockCredentialService,
      mockSubscriptionService,
      config,
      mockGatekeeperService,
      mockSubmissionService,
      mockUpliftNamingService,
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

  trait ExtendedResponses {
    self : Setup =>

    val apiIdentifier1 = "api1".asIdentifier
    val apiIdentifier2 = "api2".asIdentifier
    val standardApplicationResponse: ExtendedApplicationResponse = aNewExtendedApplicationResponse(access = Standard(), subscriptions = List(apiIdentifier1, apiIdentifier2))
    val privilegedApplicationResponse: ExtendedApplicationResponse = aNewExtendedApplicationResponse(access = Privileged())
    val ropcApplicationResponse: ExtendedApplicationResponse = aNewExtendedApplicationResponse(access = Ropc())
  }

  val authTokenHeader: (String, String) = "authorization" -> "authorizationToken"

  val credentialServiceResponseToken: ApplicationTokenResponse =
    ApplicationTokenResponse(ClientId("111"), "222", List(ClientSecretResponse(ClientSecret("3333", hashedSecret = "3333".bcrypt(4)))))

  "update approval" should {
    val termsOfUseAgreement = TermsOfUseAgreement("test@example.com", DateTimeUtils.now, "1.0".asVersion.value)
    val checkInformation = CheckInformation(
      contactDetails = Some(ContactDetails("Tester", "test@example.com", "12345677890")), termsOfUseAgreements = List(termsOfUseAgreement))
    val id = ApplicationId.random

    "fail with a 404 (not found) when id is provided but no application exists for that id" in new Setup {
      when(underTest.applicationService.fetch(id)).thenReturn(OptionT.none)

      val result = underTest.updateCheck(id)(request.withBody(Json.toJson(checkInformation)))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "successfully update approval information for application XYZ" in new Setup {
      givenUserIsAuthenticated(underTest)

      when(underTest.applicationService.fetch(eqTo(id))).thenReturn(OptionT.pure[Future](aNewApplicationResponse(appId = id)))
      when(underTest.applicationService.updateCheck(eqTo(id), eqTo(checkInformation))).thenReturn(successful(aNewApplicationResponse(appId = id)))

      val jsonBody: JsValue = Json.toJson(checkInformation)
      val result = underTest.updateCheck(id)(request.withBody(jsonBody))

      status(result) shouldBe OK
    }
  }

  "fetch application" should {
    val applicationId = ApplicationId.random

    "succeed with a 200 (ok) if the application exists for the given id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val result = underTest.fetch(applicationId)(request)

      status(result) shouldBe OK
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.fetch(applicationId)(request)

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.liftF(failed(new RuntimeException("Expected test failure"))))

      val result = underTest.fetch(applicationId)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  "fetch credentials" should {
    val applicationId = ApplicationId.random

    "succeed with a 200 (ok) when the application exists for the given id" in new Setup {
      when(mockCredentialService.fetchCredentials(applicationId)).thenReturn(successful(Some(credentialServiceResponseToken)))

      val result = underTest.fetchCredentials(applicationId)(request)

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(credentialServiceResponseToken)
    }

    "fail with a 404 (not found) when no application exists for the given id" in new Setup {
      when(mockCredentialService.fetchCredentials(applicationId)).thenReturn(successful(None))

      val result = underTest.fetchCredentials(applicationId)(request)

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(mockCredentialService.fetchCredentials(applicationId)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchCredentials(applicationId)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "addTermsOfUseAcceptance" should {
    "call applicationService correctly" in new Setup {
      val applicationId = ApplicationId.random
      val name = "bob"
      val emailAddress = "bob@example.com"
      val acceptanceDate = DateTime.now()
      val submissionId = Submission.Id.random
      val captor = ArgCaptor[TermsOfUseAcceptance]
      val addTermsOfUseAcceptanceRequest = AddTermsOfUseAcceptanceRequest(name, emailAddress, acceptanceDate, submissionId)

      when(mockApplicationService.addTermsOfUseAcceptance(eqTo(applicationId), *)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.addTermsOfUseAcceptance(applicationId)(request.withBody(Json.toJson(addTermsOfUseAcceptanceRequest)))

      status(result) shouldBe NO_CONTENT
      verify(mockApplicationService).addTermsOfUseAcceptance(eqTo(applicationId), captor.capture)

      /* Unfortunately we need to do all this rather than simply comparing the 2 objects because the DateTime instances
      * before and after serialization are not considered equal even though they have the same value
      * (known JodaTime bug) */
      val termsOfUseAcceptance = captor.value
      termsOfUseAcceptance.dateTime.getMillis shouldBe acceptanceDate.getMillis
      termsOfUseAcceptance.submissionId shouldBe submissionId
      termsOfUseAcceptance.responsibleIndividual.fullName.value shouldBe name
      termsOfUseAcceptance.responsibleIndividual.emailAddress.value shouldBe emailAddress
    }

  }

  "add collaborators with UserId" should {
    val applicationId = ApplicationId.random
    val admin = "admin@example.com"
    val email = "test@example.com"
    val role = DEVELOPER
    val isRegistered = false
    val adminsToEmail = Set.empty[String]
    val userId = UserId.random
    val addCollaboratorRequestWithUserId = AddCollaboratorRequest(Collaborator(email, role, userId), isRegistered, adminsToEmail)
    val payload = s"""{"adminEmail":"$admin", "collaborator":{"emailAddress":"$email", "role":"$role", "userId": "${userId.value}"}, "isRegistered": $isRegistered, "adminsToEmail": []}"""
    val addRequest: FakeRequest[_] => FakeRequest[JsValue] = request => request.withBody(Json.parse(payload))

    "succeed with a 200 (ok) for a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      val response = AddCollaboratorResponse(registeredUser = true)
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequestWithUserId))(*)).thenReturn(successful(response))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(response)
    }
  }

  "add collaborators" should {
    val applicationId = ApplicationId.random
    val email = "test@example.com"
    val role = DEVELOPER
    val userId = UserId.random
    val isRegistered = false
    val adminsToEmail = Set.empty[String]
    val addCollaboratorRequest = AddCollaboratorRequest(Collaborator(email, role, userId), isRegistered, adminsToEmail)
    val addRequest: FakeRequest[_] => FakeRequest[JsValue] = request => request.withBody(Json.toJson(addCollaboratorRequest))

    "succeed with a 200 (ok) for a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      val response = AddCollaboratorResponse(registeredUser = true)
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*)).thenReturn(successful(response))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      status(result) shouldBe OK
      contentAsJson(result) shouldBe Json.toJson(response)
    }

    "succeed with a 200 (ok) for a PRIVILEGED or ROPC application and the gatekeeper is logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        val response = AddCollaboratorResponse(registeredUser = true)
        when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*)).thenReturn(successful(response))

        givenUserIsAuthenticated(underTest)

        val result = underTest.addCollaborator(applicationId)(addRequest(request))

        status(result) shouldBe OK
        contentAsJson(result) shouldBe Json.toJson(response)
      })
    }

    "succeed with a 200 (ok) for a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        val response = AddCollaboratorResponse(registeredUser = true)
        when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*)).thenReturn(successful(response))

        val result = underTest.addCollaborator(applicationId)(addRequest(request))

        status(result) shouldBe OK
        contentAsJson(result) shouldBe Json.toJson(response)
      })
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*))
        .thenReturn(failed(new NotFoundException(s"application not found for id: ${applicationId.value}")))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 422 (unprocessable) if role is invalid" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val result = underTest.addCollaborator(applicationId)(request.withBody(Json.obj("emailAddress" -> s"$email", "role" -> "invalid")))

      verifyErrorResult(result, UNPROCESSABLE_ENTITY, ErrorCode.INVALID_REQUEST_PAYLOAD)
    }

    "fail with a 409 (conflict) if email already registered with different role" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*))
        .thenReturn(failed(new UserAlreadyExists))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      verifyErrorResult(result, CONFLICT, ErrorCode.USER_ALREADY_EXISTS)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.addCollaborator(eqTo(applicationId), eqTo(addCollaboratorRequest))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.addCollaborator(applicationId)(addRequest(request))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "remove collaborator by email" should {
    val applicationId = ApplicationId.random
    val admin = "admin@example.com"
    val collaborator = "dev@example.com"
    val adminsToEmailSet = Set.empty[String]
    val adminsToEmailString = ""
    val notifyCollaborator = true

    "succeed with a 204 (No Content) for a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR, UserId.random))))

      val result = underTest.deleteCollaboratorByEmail(applicationId, collaborator, adminsToEmailString, notifyCollaborator)(request)

      status(result) shouldBe NO_CONTENT
    }

    "succeed with a 204 (No Content) for a PRIVILEGED or ROPC application when the Gatekeeper is logged in" in new PrivilegedAndRopcSetup {

      givenUserIsAuthenticated(underTest)

      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(underTest.applicationService.deleteCollaborator(
          eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
          .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR, UserId.random))))

        val result = underTest.deleteCollaboratorByEmail(applicationId, collaborator, adminsToEmailString, notifyCollaborator)(request)

        status(result) shouldBe NO_CONTENT
      })
    }

    "succeed with a 204 (No Content) for a PRIVILEGED or ROPC application when the Gatekeeper is not logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        when(underTest.applicationService.deleteCollaborator(
          eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
          .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR, UserId.random))))

        val result = underTest.deleteCollaboratorByEmail(applicationId, collaborator, adminsToEmailString, notifyCollaborator)(request)

        status(result) shouldBe NO_CONTENT
      })
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(failed(new NotFoundException(s"application not found for id: ${applicationId.value}")))

      val result = underTest.deleteCollaboratorByEmail(applicationId, collaborator, adminsToEmailString, notifyCollaborator)(request)

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 403 (forbidden) if deleting the only admin" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(failed(new ApplicationNeedsAdmin))

      val result = underTest.deleteCollaboratorByEmail(applicationId, collaborator, adminsToEmailString, notifyCollaborator)(request)

      verifyErrorResult(result, FORBIDDEN, ErrorCode.APPLICATION_NEEDS_ADMIN)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.deleteCollaboratorByEmail(applicationId, collaborator, adminsToEmailString, notifyCollaborator)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "remove collaborator" should {
    val applicationId = ApplicationId.random
    val admin = "admin@example.com"
    val collaborator = "dev@example.com"
    val adminsToEmailSet = Set.empty[String]
    val notifyCollaborator = true
    lazy val myRequest =
      FakeRequest()
      .withMethod(POST)
      .withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")
      .withBody(Json.toJson(DeleteCollaboratorRequest(collaborator, adminsToEmailSet, notifyCollaborator)))
    
    "succeed with a 204 (No Content) for a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR, UserId.random))))

      val result = underTest.deleteCollaborator(applicationId)(myRequest)

      status(result) shouldBe NO_CONTENT
    }

    "succeed with a 204 (No Content) for a PRIVILEGED or ROPC application when the Gatekeeper is logged in" in new PrivilegedAndRopcSetup {
      givenUserIsAuthenticated(underTest)

      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(underTest.applicationService.deleteCollaborator(
          eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
          .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR, UserId.random))))

        val result = underTest.deleteCollaborator(applicationId)(myRequest)

        status(result) shouldBe NO_CONTENT
      })
    }

    "succeed with a 204 (No Content) for a PRIVILEGED or ROPC application when the Gatekeeper is not logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        when(underTest.applicationService.deleteCollaborator(
          eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
          .thenReturn(successful(Set(Collaborator(admin, Role.ADMINISTRATOR, UserId.random))))

        val result = underTest.deleteCollaborator(applicationId)(myRequest)

        status(result) shouldBe NO_CONTENT
      })
    }

    "fail with a 404 (not found) if no application exists for the given id" in new Setup {
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(failed(new NotFoundException(s"application not found for id: ${applicationId.value}")))

      val result = underTest.deleteCollaborator(applicationId)(myRequest)

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "fail with a 403 (forbidden) if deleting the only admin" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(failed(new ApplicationNeedsAdmin))

      val result = underTest.deleteCollaborator(applicationId)(myRequest)

      verifyErrorResult(result, FORBIDDEN, ErrorCode.APPLICATION_NEEDS_ADMIN)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(underTest.applicationService.deleteCollaborator(
        eqTo(applicationId), eqTo(collaborator), eqTo(adminsToEmailSet), eqTo(notifyCollaborator))(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.deleteCollaborator(applicationId)(myRequest)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }
  }

  "add client secret" should {
    val applicationId = ApplicationId.random
    val applicationTokensResponse =
      ApplicationTokenResponse(ClientId("clientId"), "token", List(ClientSecretResponse(aSecret("secret1")), ClientSecretResponse(aSecret("secret2"))))
    val secretRequest = ClientSecretRequest("actor@example.com")

    "succeed with a 200 (ok) when the application exists for the given id" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(successful(applicationTokensResponse))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe OK
        contentAsJson(result) shouldBe Json.toJson(applicationTokensResponse)
      })
    }

    "succeed with a 200 (ok) when request originates from outside gatekeeper" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(successful(applicationTokensResponse))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe OK
        contentAsJson(result) shouldBe Json.toJson(applicationTokensResponse)
      })
    }

    "fail with a 403 (Forbidden) when the environment has already the maximum number of secrets set" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(failed(new ClientSecretsLimitExceeded))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        verifyErrorResult(result, FORBIDDEN, ErrorCode.CLIENT_SECRET_LIMIT_EXCEEDED)
      })
    }

    "fail with a 404 (not found) when no application exists for the given id" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(failed(new NotFoundException("application not found")))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
      })
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.addClientSecret(eqTo(applicationId), eqTo(secretRequest))(*))
          .thenReturn(failed(new RuntimeException))

        val result = underTest.addClientSecret(applicationId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe INTERNAL_SERVER_ERROR
      })
    }

  }

  "deleteClientSecret" should {

    val applicationId = ApplicationId.random
    val clientSecretId = ju.UUID.randomUUID().toString
    val actorEmailAddress = "actor@example.com"
    val secretRequest = DeleteClientSecretRequest(actorEmailAddress)
    val tokenResponse = ApplicationTokenResponse(ClientId("aaa"), "bbb", List.empty)

    "succeed with a 204 for a STANDARD application" in new Setup {

      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockCredentialService.deleteClientSecret(eqTo(applicationId), eqTo(clientSecretId), eqTo(actorEmailAddress))(*))
        .thenReturn(successful(tokenResponse))

      val result = underTest.deleteClientSecret(applicationId, clientSecretId)(request.withBody(Json.toJson(secretRequest)))

      status(result) shouldBe NO_CONTENT
    }

    "succeed with a 204 (No Content) for a PRIVILEGED or ROPC application when the Gatekeeper is logged in" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
        when(mockCredentialService.deleteClientSecret(eqTo(applicationId), eqTo(clientSecretId), eqTo(actorEmailAddress))(*))
          .thenReturn(successful(tokenResponse))

        val result = underTest.deleteClientSecret(applicationId, clientSecretId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe NO_CONTENT
      })
    }

    "succeed with a 204 for a PRIVILEGED or ROPC application when the request originates outside gatekeeper" in new PrivilegedAndRopcSetup {
      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
        when(mockCredentialService.deleteClientSecret(eqTo(applicationId), eqTo(clientSecretId), eqTo(actorEmailAddress))(*))
          .thenReturn(successful(tokenResponse))

        val result = underTest.deleteClientSecret(applicationId, clientSecretId)(request.withBody(Json.toJson(secretRequest)))

        status(result) shouldBe NO_CONTENT
      })
    }
  }

  "validate credentials" should {
    val validation = ValidationRequest(ClientId("clientId"), "clientSecret")
    val payload = s"""{"clientId":"${validation.clientId.value}", "clientSecret":"${validation.clientSecret}"}"""

    "succeed with a 200 (ok) if the credentials are valid for an application" in new Setup {
      when(mockCredentialService.validateCredentials(validation)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))

      val result = underTest.validateCredentials(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK
    }

    "fail with a 401 if credentials are invalid for an application" in new Setup {

      when(mockCredentialService.validateCredentials(validation)).thenReturn(OptionT.none)

      val result = underTest.validateCredentials(request.withBody(Json.parse(payload)))

      verifyErrorResult(result, UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      when(mockCredentialService.validateCredentials(validation)).thenReturn(OptionT.liftF(failed(new RuntimeException("Expected test failure"))))

      val result =underTest.validateCredentials(request.withBody(Json.parse(payload)))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "validate name" should {
    "Allow a valid app" in new Setup {

      val applicationName = "my valid app name"
      val appId = ApplicationId.random
      val payload = s"""{"applicationName":"${applicationName}", "environment":"PRODUCTION", "selfApplicationId" : "${appId.value.toString}" }"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ValidName))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.obj()

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName), eqTo(Some(appId)))
    }

    "Allow a valid app with an optional selfApplicationId" in new Setup {
      val applicationName = "my valid app name"
      val payload = s"""{"applicationName":"${applicationName}", "environment":"PRODUCTION"}"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(ValidName))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.obj()

      verify(mockUpliftNamingService).validateApplicationName(*, eqTo(None))
    }

    "Reject an app name as it contains a block bit of text" in new Setup {
      val applicationName = "my invalid HMRC app name"
      val payload = s"""{"applicationName":"${applicationName}"}"""

      when(mockUpliftNamingService.validateApplicationName(*, *))
        .thenReturn(successful(InvalidName))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.obj("errors" -> Json.obj("invalidName" -> true, "duplicateName" -> false))

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName),eqTo(None))
    }

    "Reject an app name as it is a duplicate name" in new Setup {
      val applicationName = "my duplicate app name"
      val payload = s"""{"applicationName":"${applicationName}"}"""

      when(mockUpliftNamingService.validateApplicationName(*,*))
        .thenReturn(successful(DuplicateName))

      private val result =underTest.validateApplicationName(request.withBody(Json.parse(payload)))

      status(result) shouldBe OK

      contentAsJson(result) shouldBe Json.obj("errors" -> Json.obj("invalidName" -> false, "duplicateName" -> true))

      verify(mockUpliftNamingService).validateApplicationName(eqTo(applicationName),eqTo(None))
    }
  }

  "query dispatcher" should {
    val clientId = ClientId("A123XC")
    val serverToken = "b3c83934c02df8b111e7f9f8700000"

    trait LastAccessedSetup extends Setup {
      val updatedLastAccessTime: DateTime = DateTime.now()
      val lastAccessTime: DateTime = updatedLastAccessTime.minusDays(10) //scalastyle:ignore magic.number
      val applicationId: ApplicationId = ApplicationId.random

      val applicationResponse: ApplicationResponse = aNewApplicationResponse().copy(id = applicationId, lastAccess = Some(lastAccessTime))
      val updatedApplicationResponse: ExtendedApplicationResponse = extendedApplicationResponseFromApplicationResponse(applicationResponse).copy(lastAccess = Some(updatedLastAccessTime))
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

      private val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientId.value}"))

      validateResult(result, OK, Some(s"max-age=$applicationTtlInSecs"), None)
    }

    "retrieve by server token" in new Setup {
      when(underTest.applicationService.fetchByServerToken(serverToken)).thenReturn(Future(Some(aNewApplicationResponse())))

      private val scenarios =
        Table(
          ("serverTokenHeader", "expectedVaryHeader"),
          ("X-Server-Token", "X-server-token"),
          ("X-SERVER-TOKEN", "X-server-token"))

      forAll(scenarios) { (serverTokenHeader, expectedVaryHeader) =>
        val result = underTest.queryDispatcher()(request.withHeaders(serverTokenHeader -> serverToken))

        validateResult(result, OK, Some(s"max-age=$applicationTtlInSecs"), Some(expectedVaryHeader))
      }
    }

    "retrieve all" in new Setup {
      when(underTest.applicationService.fetchAll()).thenReturn(Future(List(aNewApplicationResponse(), aNewApplicationResponse())))

      private val result = underTest.queryDispatcher()(FakeRequest())

      validateResult(result, OK, None, None)
      contentAsJson(result).as[Seq[JsValue]] should have size 2
    }

    "retrieve when no subscriptions" in new Setup {
      when(underTest.applicationService.fetchAllWithNoSubscriptions()).thenReturn(Future(List(aNewApplicationResponse())))

      private val result = underTest.queryDispatcher()(FakeRequest("GET", s"?noSubscriptions=true"))

      validateResult(result, OK, None, None)
    }

    "fail with a 500 (internal server error) when an exception is thrown from fetchAll" in new Setup {
      when(underTest.applicationService.fetchAll()).thenReturn(failed(new RuntimeException("Expected test exception")))

      private val result = underTest.queryDispatcher()(FakeRequest())

      validateResult(result, INTERNAL_SERVER_ERROR, None, None)
    }

    "fail with a 500 (internal server error) when a clientId is supplied" in new Setup {
      when(underTest.applicationService.fetchByClientId(clientId)).thenReturn(failed(new RuntimeException("Expected test exception")))

      private val result = underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientId.value}"))

      validateResult(result, INTERNAL_SERVER_ERROR, None, None)
    }

    "update last accessed time and server token usage when an API gateway retrieves Application by Server Token" in new LastAccessedSetup {
      val scenarios =
        Table(
          ("headers", "expectedLastAccessTime", "shouldUpdate"),
          (Seq(SERVER_TOKEN_HEADER -> serverToken, USER_AGENT -> "APIPlatformAuthorizer"), updatedLastAccessTime.getMillis, true),
          (Seq(SERVER_TOKEN_HEADER -> serverToken, USER_AGENT -> "APIPlatformAuthorizer,foobar"), updatedLastAccessTime.getMillis, true),
          (Seq(SERVER_TOKEN_HEADER -> serverToken, USER_AGENT -> "foobar,APIPlatformAuthorizer"), updatedLastAccessTime.getMillis,true),
          (Seq(SERVER_TOKEN_HEADER -> serverToken, USER_AGENT -> "foobar"), lastAccessTime.getMillis, false),
          (Seq(SERVER_TOKEN_HEADER -> serverToken), lastAccessTime.getMillis, false)
        )

      forAll(scenarios) { (headers, expectedLastAccessTime, shouldUpdate) =>
        when(underTest.applicationService.fetchByServerToken(serverToken)).thenReturn(Future(Some(applicationResponse)))
        when(underTest.applicationService.recordApplicationUsage(applicationId)).thenReturn(Future(updatedApplicationResponse))
        when(underTest.applicationService.recordServerTokenUsage(applicationId)).thenReturn(Future(updatedApplicationResponse))

        val result = underTest.queryDispatcher()(request.withHeaders(headers: _*))
        val actualLastAccessTime = (contentAsJson(result) \ "lastAccess").as[Long]

        actualLastAccessTime shouldBe expectedLastAccessTime
        validateResult(result, OK, Some(s"max-age=$applicationTtlInSecs"), Some(SERVER_TOKEN_HEADER))
        if(shouldUpdate) {
          verify(underTest.applicationService).recordServerTokenUsage(eqTo(applicationId))
        }
        reset(underTest.applicationService)
      }
    }

    "update last accessed time when an API gateway retrieves Application by Client Id" in new LastAccessedSetup {
      val scenarios =
        Table(
          ("headers", "expectedLastAccessTime","shouldUpdate"),
          (Seq(USER_AGENT -> "APIPlatformAuthorizer"), updatedLastAccessTime.getMillis, true),
          (Seq(USER_AGENT -> "APIPlatformAuthorizer,foobar"), updatedLastAccessTime.getMillis, true),
          (Seq(USER_AGENT -> "foobar,APIPlatformAuthorizer"), updatedLastAccessTime.getMillis, true),
          (Seq(USER_AGENT -> "foobar"), lastAccessTime.getMillis,false),
          (Seq(), lastAccessTime.getMillis,false)
        )

      forAll(scenarios) { (headers, expectedLastAccessTime,shouldUpdate) =>
        when(underTest.applicationService.fetchByClientId(clientId)).thenReturn(Future(Some(applicationResponse)))
        when(underTest.applicationService.recordApplicationUsage(applicationId)).thenReturn(Future(updatedApplicationResponse))
        when(underTest.applicationService.recordServerTokenUsage(applicationId)).thenReturn(Future(updatedApplicationResponse))

        val result =
          underTest.queryDispatcher()(FakeRequest("GET", s"?clientId=${clientId.value}").withHeaders(headers: _*))

        validateResult(result, OK, Some(s"max-age=$applicationTtlInSecs"), None)
        (contentAsJson(result) \ "lastAccess").as[Long] shouldBe expectedLastAccessTime
        if(shouldUpdate) {
          verify(underTest.applicationService).recordApplicationUsage(eqTo(applicationId))
        }          
        reset(underTest.applicationService)
      }
    }

    val emailAddress = "dev@example.com"
    val userId = UserId.random
    val environment = "PRODUCTION"
    val queryRequest = FakeRequest("GET", s"?emailAddress=$emailAddress")

    "succeed with a 200 when applications are found for a collaborator by email address" in new Setup {
      val standardApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Standard())
      val privilegedApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Privileged())
      val ropcApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Ropc())

      when(underTest.applicationService.fetchAllForCollaborator(emailAddress))
        .thenReturn(successful(List(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)))

      status(underTest.queryDispatcher()(queryRequest)) shouldBe OK
    }

    "succeed with a 200 when no applications are found for the collaborator by email address" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(emailAddress)).thenReturn(successful(Nil))

      val result = underTest.queryDispatcher()(queryRequest)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(emailAddress)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.queryDispatcher()(queryRequest)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

    "succeed with a 200 when applications are found for the collaborator by email address and environment" in new Setup {
      val queryRequestWithEnvironment = FakeRequest("GET", s"?emailAddress=$emailAddress&environment=$environment")
      val standardApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Standard())
      val privilegedApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Privileged())
      val ropcApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Ropc())

      when(underTest.applicationService.fetchAllForCollaboratorAndEnvironment(emailAddress, environment))
        .thenReturn(successful(List(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)))

      status(underTest.queryDispatcher()(queryRequestWithEnvironment)) shouldBe OK
    }
    
    "succeed with a 200 when applications are found for the collaborator by userId and environment" in new Setup with ExtendedResponses {
      val queryRequestWithEnvironment = FakeRequest("GET", s"?userId=${userId.asText}&environment=$environment")

      when(underTest.applicationService.fetchAllForUserIdAndEnvironment(userId, environment))
        .thenReturn(successful(List(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)))

      status(underTest.queryDispatcher()(queryRequestWithEnvironment)) shouldBe OK
    }
     
    "fail with a BadRequest when applications are requested for the collaborator by userId and environment but the userId is badly formed" in new Setup with ExtendedResponses {
      val queryRequestWithEnvironment = FakeRequest("GET", s"?userId=XXX&environment=$environment")

      when(underTest.applicationService.fetchAllForUserIdAndEnvironment(userId, environment))
        .thenReturn(successful(List(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)))

      status(underTest.queryDispatcher()(queryRequestWithEnvironment)) shouldBe BAD_REQUEST
    }
  }

  "fetchAllForCollaborator" should {
    val userId = UserId.random

    "succeed with a 200 when applications are found for the collaborator by user id" in new Setup with ExtendedResponses {
      when(underTest.applicationService.fetchAllForCollaborator(userId))
        .thenReturn(successful(List(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)))

      status(underTest.fetchAllForCollaborator(userId)(request)) shouldBe OK
    }

    "succeed with a 200 when no applications are found for the collaborator by user id" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(userId)).thenReturn(successful(Nil))

      val result = underTest.fetchAllForCollaborator(userId)(request)

      status(result) shouldBe OK
      contentAsString(result) shouldBe "[]"
    }

    "fail with a 500 when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetchAllForCollaborator(userId)).thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.fetchAllForCollaborator(userId)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
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
        val response: List[ApplicationResponse] = List(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)

        when(underTest.applicationService.fetchAllBySubscription(subscribesTo.asContext)).thenReturn(successful(response))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe OK

        contentAsJson(result) shouldBe Json.toJson(response)
      }

      "succeed with a 200 (ok) when no applications are found" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(subscribesTo.asContext)).thenReturn(successful(List.empty))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe OK

        contentAsString(result) shouldBe "[]"
      }

      "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(subscribesTo.asContext)).thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }

    "given a version" should {
      val version = "1.0"
      val queryRequest = FakeRequest("GET", s"?subscribesTo=$subscribesTo&version=$version")
      val apiIdentifier = subscribesTo.asIdentifier(version)

      "succeed with a 200 (ok) when applications are found" in new Setup {
        val standardApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Standard())
        val privilegedApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Privileged())
        val ropcApplicationResponse: ApplicationResponse = aNewApplicationResponse(access = Ropc())
        val response: List[ApplicationResponse] = List(standardApplicationResponse, privilegedApplicationResponse, ropcApplicationResponse)

        when(underTest.applicationService.fetchAllBySubscription(apiIdentifier)).thenReturn(successful(response))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe OK

        contentAsJson(result) shouldBe Json.toJson(response)

      }

      "succeed with a 200 (ok) when no applications are found" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(apiIdentifier)).thenReturn(successful(List.empty))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe OK

        contentAsString(result) shouldBe "[]"
      }

      "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
        when(underTest.applicationService.fetchAllBySubscription(apiIdentifier)).thenReturn(failed(new RuntimeException("Expected test failure")))

        val result = underTest.queryDispatcher()(queryRequest)

        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }
  }

  "isSubscribed" should {

    val applicationId: ApplicationId = ApplicationId.random
    val context = "context".asContext
    val version = "1.0".asVersion
    val api = ApiIdentifier(context, version)

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
      verifyErrorResult(result, NOT_FOUND, ErrorCode.SUBSCRIPTION_NOT_FOUND)
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

    val applicationId = ApplicationId.random
    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId)))
        .thenReturn(failed(new NotFoundException("application doesn't exist")))

      val result = underTest.fetchAllSubscriptions(applicationId)(request)

      status(result) shouldBe NOT_FOUND
    }

    "succeed with a 200 (ok) when subscriptions are found for the application" in new Setup {
      when(mockSubscriptionService.fetchAllSubscriptionsForApplication(eqTo(applicationId)))
        .thenReturn(successful(Set(anAPI())))

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

    "succeed with a 200 (ok) when subscriptions are found for the application" in new Setup {

      val subscriptionData = List(aSubcriptionData(), aSubcriptionData())

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

  "createSubscriptionForApplication" should {
    val applicationId = ApplicationId.random
    val body = anAPIJson()

    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "succeed with a 204 (no content) when a subscription is successfully added to a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(applicationId), *)(*))
        .thenReturn(successful(HasSucceeded))

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe NO_CONTENT
    }

    "succeed with a 204 (no content) when a subscription is successfully added to a PRIVILEGED or ROPC application and the gatekeeper is logged in" in
      new PrivilegedAndRopcSetup {

        givenUserIsAuthenticated(underTest)

        testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
          when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(applicationId), *)(*))
            .thenReturn(successful(HasSucceeded))

          status(underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))) shouldBe NO_CONTENT
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

      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.createSubscriptionForApplicationMinusChecks(eqTo(applicationId), *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.createSubscriptionForApplication(applicationId)(request.withBody(Json.parse(body)))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "removeSubscriptionForApplication" should {
    val applicationId = ApplicationId.random

    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.removeSubscriptionForApplication(applicationId, "some-context".asContext, "1.0".asVersion)(request)

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "succeed with a 204 (no content) when a subscription is successfully removed from a STANDARD application" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.removeSubscriptionForApplication(eqTo(applicationId), *)(*))
        .thenReturn(successful(HasSucceeded))

      val result = underTest.removeSubscriptionForApplication(applicationId, "some-context".asContext, "1.0".asVersion)(request)

      status(result) shouldBe NO_CONTENT
    }

    "succeed with a 204 (no content) when a subscription is successfully removed from a PRIVILEGED or ROPC application and the gatekeeper is logged in" in
      new PrivilegedAndRopcSetup {

        givenUserIsAuthenticated(underTest)

        testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId, {
          when(mockSubscriptionService.removeSubscriptionForApplication(eqTo(applicationId), *)(*))
            .thenReturn(successful(HasSucceeded))

          status(underTest.removeSubscriptionForApplication(applicationId, "some-context".asContext, "1.0".asVersion)(request)) shouldBe NO_CONTENT
        })
      }

    "fail with a 401 (unauthorized) when trying to remove a subscription from a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in
      new PrivilegedAndRopcSetup {
        testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId, {
          assertThrows[SessionRecordNotFound](await(underTest.removeSubscriptionForApplication(applicationId, "some-context".asContext, "1.0".asVersion)(request)))
        })
      }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      when(underTest.applicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](aNewApplicationResponse()))
      when(mockSubscriptionService.removeSubscriptionForApplication(eqTo(applicationId), *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.removeSubscriptionForApplication(applicationId, "some-context".asContext, "1.0".asVersion)(request)

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

  }

  "update rate limit tier" should {

    val applicationId = ApplicationId.random
    val invalidUpdateRateLimitTierJson = Json.parse("""{ "foo" : "bar" }""")
    val validUpdateRateLimitTierJson = Json.parse("""{ "rateLimitTier" : "silver" }""")

    "fail with a 422 (unprocessable entity) when request json is invalid" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = underTest.updateRateLimitTier(applicationId)(request.withBody(invalidUpdateRateLimitTierJson))

      status(result) shouldBe UNPROCESSABLE_ENTITY
      verify(underTest.applicationService, never).updateRateLimitTier(eqTo(applicationId), eqTo(SILVER))(*)
    }

    "fail with a 422 (unprocessable entity) when request json is valid but rate limit tier is an invalid value" in new Setup {

      givenUserIsAuthenticated(underTest)

      val result = underTest.updateRateLimitTier(applicationId)(request.withBody(Json.parse("""{ "rateLimitTier" : "multicoloured" }""")))
      status(result) shouldBe UNPROCESSABLE_ENTITY
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

      when(underTest.applicationService.updateRateLimitTier(eqTo(applicationId), eqTo(SILVER))(*)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.updateRateLimitTier(applicationId)(request.withBody(validUpdateRateLimitTierJson))

      status(result) shouldBe NO_CONTENT
      verify(underTest.applicationService).updateRateLimitTier(eqTo(applicationId), eqTo(SILVER))(*)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {

      givenUserIsAuthenticated(underTest)

      when(underTest.applicationService.updateRateLimitTier(eqTo(applicationId), eqTo(SILVER))(*))
        .thenReturn(failed(new RuntimeException("Expected test exception")))

      val result = underTest.updateRateLimitTier(applicationId)(request.withBody(validUpdateRateLimitTierJson))

      status(result) shouldBe INTERNAL_SERVER_ERROR
    }

    "fail with a 401 (Unauthorized) when the request is done without a gatekeeper token" in new Setup {

      givenUserIsNotAuthenticated(underTest)

      when(underTest.applicationService.updateRateLimitTier(eqTo(applicationId), eqTo(SILVER))(*)).thenReturn(successful(mock[ApplicationData]))

      assertThrows[SessionRecordNotFound](await(underTest.updateRateLimitTier(applicationId)(request.withBody(validUpdateRateLimitTierJson))))
    }
  }

  "update IP allowlist" should {
    "succeed with a 204 (no content) when the IP allowlist is successfully added to the application" in new Setup {
      val applicationId: ApplicationId = ApplicationId.random
      val validUpdateIpAllowlistJson: JsValue = Json.parse("""{ "required": false, "allowlist" : ["192.168.100.0/22", "192.168.104.1/32"] }""")
      when(underTest.applicationService.updateIpAllowlist(eqTo(applicationId), *)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.updateIpAllowlist(applicationId)(request.withBody(validUpdateIpAllowlistJson))

      status(result) shouldBe NO_CONTENT
    }

    "fail when the JSON message is invalid" in new Setup {
      val applicationId: ApplicationId = ApplicationId.random
      val validUpdateIpAllowlistJson: JsValue = Json.parse("""{ "required": false, "foo" : ["192.168.100.0/22", "192.168.104.1/32"] }""")
      when(underTest.applicationService.updateIpAllowlist(eqTo(applicationId), *)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.updateIpAllowlist(applicationId)(request.withBody(validUpdateIpAllowlistJson))

      verifyErrorResult(result, UNPROCESSABLE_ENTITY, INVALID_REQUEST_PAYLOAD)
    }

    "fail when the IP allowlist is invalid" in new Setup {
      val applicationId: ApplicationId = ApplicationId.random
      val validUpdateIpAllowlistJson: JsValue = Json.parse("""{ "required": false, "allowlist" : ["392.168.100.0/22"] }""")
      val errorMessage = "invalid IP allowlist"
      when(underTest.applicationService.updateIpAllowlist(eqTo(applicationId), *))
        .thenReturn(Future.failed(InvalidIpAllowlistException(errorMessage)))

      val result = underTest.updateIpAllowlist(applicationId)(request.withBody(validUpdateIpAllowlistJson))

      verifyErrorResult(result, BAD_REQUEST, INVALID_IP_ALLOWLIST)
    }
  }

  "update Grant Length" should {
    "succeed with a 204 (no content) when the Grant Length is successfully updated to the application" in new Setup {
      val applicationId: ApplicationId = ApplicationId.random
      val validUpdateGrantLengthJson: JsValue = Json.parse("""{ "grantLengthInDays": 5470 }""")
      when(underTest.applicationService.updateGrantLength(eqTo(applicationId), *)).thenReturn(successful(mock[ApplicationData]))

      val result = underTest.updateGrantLength(applicationId)(request.withBody(validUpdateGrantLengthJson))

      status(result) shouldBe NO_CONTENT
    }

    "fail with a exception when the Grant Length in months with  less than or equal to 0 updated to the application" in new Setup {
      val applicationId: ApplicationId = ApplicationId.random
      val invalidUpdateGrantLengthJson: JsValue = Json.parse("""{ "grantLengthInDays": 0 }""")
      when(underTest.applicationService.updateGrantLength(eqTo(applicationId), *)).thenReturn(successful(mock[ApplicationData]))

      val error = intercept[InvalidGrantLengthException] {
        await(underTest.updateGrantLength(applicationId)(request.withBody(invalidUpdateGrantLengthJson)))
      }

      error.getMessage shouldBe "Grant Length in Days cannot be less than or equal to zero"
    }
  }

  "Search" should {
    "pass an ApplicationSearch object to applicationService" in new Setup {
      val req: FakeRequest[AnyContentAsEmpty.type] =
        FakeRequest("GET", "/applications?apiSubscriptions=ANYSUB&page=1&pageSize=100")
          .withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

      // scalastyle:off magic.number
      when(underTest.applicationService.searchApplications(any[ApplicationSearch]))
        .thenReturn(Future(PaginatedApplicationResponse(applications = List.empty, page = 1, pageSize = 100, total = 0, matching = 0)))

      val result = underTest.searchApplications(req)

      status(result) shouldBe OK
    }

    "return BAD REQUEST if date/time cannot be parsed for lastUseBefore query parameter" in new Setup {
      val req: FakeRequest[AnyContentAsEmpty.type] = FakeRequest("GET", "/applications?lastUseBefore=foo")

      val result = underTest.searchApplications(req)

      verifyZeroInteractions(mockApplicationService)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "notStrideUserDeleteApplication" should {
    val application = aNewApplicationResponse(environment = SANDBOX, state = ApplicationState(State.PRODUCTION))
    val applicationId = application.id
    val gatekeeperUserId = "big.boss.gatekeeper"
    val requestedByEmailAddress = "admin@example.com"
    val deleteRequest = DeleteApplicationRequest(gatekeeperUserId, requestedByEmailAddress)
    def base64Encode(stringToEncode: String): String = new String(Base64.getEncoder.encode(stringToEncode.getBytes), StandardCharsets.UTF_8)

    "succeed when a sandbox application is successfully deleted" in new SandboxDeleteApplications {
      when(mockApplicationService.fetch(applicationId)).thenReturn(OptionT.pure[Future](application))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(mockApplicationService).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }

    "succeed when a principal application is in TESTING state is deleted" in new ProductionDeleteApplications {
      val inTesting = aNewApplicationResponse(state = ApplicationState(name = State.TESTING), environment = PRODUCTION)
      val inTestingId = application.id
      
      when(mockApplicationService.fetch(inTestingId)).thenReturn(OptionT.pure[Future](inTesting))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(inTestingId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(mockApplicationService).deleteApplication(eqTo(inTestingId), eqTo(None), * )(*)
    }
    
    "succeed when a principal application is in PENDING_GATEKEEPER_APPROVAL state is deleted" in new ProductionDeleteApplications {
      val inPending = aNewApplicationResponse(state = ApplicationState(name = State.PENDING_GATEKEEPER_APPROVAL), environment = PRODUCTION)
      val inPendingId = application.id
      
      when(mockApplicationService.fetch(inPendingId)).thenReturn(OptionT.pure[Future](inPending))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(inPendingId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(mockApplicationService).deleteApplication(eqTo(inPendingId), eqTo(None), * )(*)
    }
    
    "succeed when a principal application is in PENDING_REQUESTER_VERIFICATION state is deleted" in new ProductionDeleteApplications {
      val inPending = aNewApplicationResponse(state = ApplicationState(name = State.PENDING_REQUESTER_VERIFICATION), environment = PRODUCTION)
      val inPendingId = application.id
      
      when(mockApplicationService.fetch(inPendingId)).thenReturn(OptionT.pure[Future](inPending))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(inPendingId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(mockApplicationService).deleteApplication(eqTo(inPendingId), eqTo(None), * )(*)
    }

    "fail when a principal application is in PRODUCTION state is deleted" in new ProductionDeleteApplications {
      val inProd = aNewApplicationResponse(state = ApplicationState(name = State.PRODUCTION), environment = PRODUCTION)
      val inProdId = application.id
      
      when(mockApplicationService.fetch(inProdId)).thenReturn(OptionT.pure[Future](inProd))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(inProdId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe BAD_REQUEST
      verify(mockApplicationService, never).deleteApplication(*[ApplicationId], *, * )(*)
    }

    "fail with a 400 error when a production application is requested to be deleted and authorisation key is missing" in new ProductionDeleteApplications {
      when(mockApplicationService.fetch(*[ApplicationId])).thenReturn(OptionT.pure[Future](application))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe BAD_REQUEST
      verify(mockApplicationService, never).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }

    "fail with an authorisation error when a production application is requested to be deleted and auth key is invalid" in new ProductionDeleteApplications {
      givenUserIsNotAuthenticated(underTest)
      when(mockApplicationService.fetch(*[ApplicationId])).thenReturn(OptionT.pure[Future](application))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))
      when(mockAuthConfig.authorisationKey).thenReturn("foo")

      val error = intercept[AuthorisationException] {
        await(underTest.deleteApplication(applicationId)(request
          .withBody(Json.toJson(deleteRequest))
          .withHeaders(AUTHORIZATION -> "bar")
          .asInstanceOf[FakeRequest[AnyContent]]))
      }

      error.getMessage shouldBe "Session record not found"
      verify(mockApplicationService, never).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }

    "succeed when a production application is requested to be deleted and authorisation key is valid" in new ProductionDeleteApplications {
      when(mockApplicationService.fetch(*[ApplicationId])).thenReturn(OptionT.pure[Future](application))
      when(mockApplicationService.deleteApplication(*[ApplicationId], *, * ) (*)).thenReturn(successful(Deleted))
      val authorisationKey = "foo"
      when(mockAuthConfig.authorisationKey).thenReturn(authorisationKey)

      val result = underTest.deleteApplication(applicationId)(request
        .withBody(Json.toJson(deleteRequest))
        .withHeaders(AUTHORIZATION -> base64Encode(authorisationKey))
        .asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NO_CONTENT
      verify(mockApplicationService).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }

    "fail with a 404 error when a nonexistent sandbox application is requested to be deleted" in new SandboxDeleteApplications {
      when(mockApplicationService.fetch(applicationId)).thenReturn(OptionT.none)

      val result = underTest.deleteApplication(applicationId)(request.withBody(Json.toJson(deleteRequest)).asInstanceOf[FakeRequest[AnyContent]])

      status(result) shouldBe NOT_FOUND
      verify(mockApplicationService, never).deleteApplication(eqTo(applicationId), eqTo(None), * )(*)
    }
  }

  "strideUserDeleteApplication" should {
    val applicationId = ApplicationId.random
    val gatekeeperUserId = "big.boss.gatekeeper"
    val requestedByEmailAddress = "admin@example.com"
    val deleteRequest = DeleteApplicationRequest(gatekeeperUserId, requestedByEmailAddress)

    "succeed with a 204 (no content) when the application is successfully deleted" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(mockGatekeeperService.deleteApplication(*[ApplicationId], *)(*)).thenReturn(successful(Deleted))

      val result = underTest.deleteApplication(applicationId).apply(request
        .withHeaders(AUTHORIZATION -> "foo")
        .withBody(AnyContentAsJson(Json.toJson(deleteRequest))))

      status(result) shouldBe NO_CONTENT
      verify(mockGatekeeperService).deleteApplication(eqTo(applicationId), eqTo(deleteRequest)) (*)
    }

    "fail with a 500 (internal server error) when an exception is thrown" in new Setup {
      givenUserIsAuthenticated(underTest)
      when(mockGatekeeperService.deleteApplication(*[ApplicationId], *)(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      val result = underTest.deleteApplication(applicationId).apply(request
        .withHeaders(AUTHORIZATION -> "foo")
        .withBody(AnyContentAsJson(Json.toJson(deleteRequest))))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      verify(mockGatekeeperService).deleteApplication(eqTo(applicationId), eqTo(deleteRequest)) (*)
    }
  }
}

trait ControllerTestData {
  val collaborators: Set[Collaborator] = Set(
    Collaborator("admin@example.com", ADMINISTRATOR,UserId.random),
    Collaborator("dev@example.com", DEVELOPER, UserId.random)
  )

  val standardAccess = Standard(List("http://example.com/redirect"), Some("http://example.com/terms"), Some("http://example.com/privacy"))
  val privilegedAccess = Privileged(scopes = Set("scope1"))
  val ropcAccess = Ropc()

  def anAPI() = {
    "some-context".asIdentifier("1.0")
  }

  def aSubcriptionData() = {
    SubscriptionData(anAPI(), Set(ApplicationId.random, ApplicationId.random))
  }

  def anAPIJson() = {
    """{ "context" : "some-context", "version" : "1.0" }"""
  }

  def aNewApplicationResponse(access: Access = standardAccess, environment: Environment = Environment.PRODUCTION, appId: ApplicationId = ApplicationId.random, state: ApplicationState = ApplicationState(State.TESTING)) = {
    val grantLengthInDays = 547
    new ApplicationResponse(
      appId,
      ClientId("clientId"),
      "gatewayId",
      "My Application",
      environment.toString,
      Some("Description"),
      collaborators,
      DateTimeUtils.now,
      Some(DateTimeUtils.now),
      grantLengthInDays,
      None,
      standardAccess.redirectUris,
      standardAccess.termsAndConditionsUrl,
      standardAccess.privacyPolicyUrl,
      access,
      state
    )
  }

  def aNewExtendedApplicationResponse(access: Access, environment: Environment = Environment.PRODUCTION, subscriptions: List[ApiIdentifier] = List.empty) = 
    extendedApplicationResponseFromApplicationResponse(aNewApplicationResponse(access, environment)).copy(subscriptions = subscriptions)

  def extendedApplicationResponseFromApplicationResponse(app: ApplicationResponse) = {
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
      app.grantLength,
      app.redirectUris,
      app.termsAndConditionsUrl,
      app.privacyPolicyUrl,
      app.access,
      app.state,
      app.rateLimitTier,
      app.checkInformation,
      app.blocked,
      app.trusted,
      ju.UUID.randomUUID().toString,
      List.empty
    )
  }
}
