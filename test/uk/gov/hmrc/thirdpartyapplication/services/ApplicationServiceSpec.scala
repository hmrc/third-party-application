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

package uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID
import java.util.concurrent.{TimeUnit, TimeoutException}

import akka.actor.ActorSystem
import cats.implicits._
import com.github.t3hnar.bcrypt._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import org.joda.time.{DateTime, DateTimeUtils}
import org.mockito.Mockito
import org.scalatest.BeforeAndAfterAll
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.{ForbiddenException, HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector.{EmailConnector, ThirdPartyDelegatedAuthorityConnector, TotpConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.{AddCollaboratorRequest, AddCollaboratorResponse, DeleteApplicationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.ActorType.{COLLABORATOR, GATEKEEPER}
import uk.gov.hmrc.thirdpartyapplication.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{RateLimitTier, SILVER}
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models.State._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.{StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, CredentialGenerator}
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.ApiSubscriptionFieldsConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class ApplicationServiceSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  private val loggedInUser = "loggedin@example.com"
  val serverTokenLastAccess = DateTime.now
  private val productionToken = EnvironmentToken("aaa", "bbb", List(aSecret("secret1"), aSecret("secret2")), Some(serverTokenLastAccess))

  trait Setup extends AuditServiceMockModule
    with ApiGatewayStoreMockModule
    with ApiSubscriptionFieldsConnectorMockModule
    with ApplicationRepositoryMockModule with TokenServiceMockModule {

    val actorSystem: ActorSystem = ActorSystem("System")

    val applicationId: UUID = UUID.randomUUID()
    val applicationData: ApplicationData = anApplicationData(applicationId)

    lazy val locked = false
    protected val mockitoTimeout = 1000
    val mockSubscriptionRepository: SubscriptionRepository = mock[SubscriptionRepository]
    val mockStateHistoryRepository: StateHistoryRepository = mock[StateHistoryRepository]
    val mockEmailConnector: EmailConnector = mock[EmailConnector]
    val mockTotpConnector: TotpConnector = mock[TotpConnector]
    val mockLockKeeper = new MockLockKeeper(locked)
    val response = mock[HttpResponse]
    val mockThirdPartyDelegatedAuthorityConnector = mock[ThirdPartyDelegatedAuthorityConnector]
    val mockGatekeeperService = mock[GatekeeperService]
    val mockApiPlatformEventService = mock[ApiPlatformEventService]

    val applicationResponseCreator = new ApplicationResponseCreator()

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser,
      LOGGED_IN_USER_NAME_HEADER -> "John Smith"
    )

    val mockCredentialGenerator: CredentialGenerator = mock[CredentialGenerator]

    val mockNameValidationConfig = mock[ApplicationNameValidationConfig]

    when(mockNameValidationConfig.validateForDuplicateAppNames)
      .thenReturn(true)

    val underTest = new ApplicationService(
      ApplicationRepoMock.aMock,
      mockStateHistoryRepository,
      mockSubscriptionRepository,
      AuditServiceMock.aMock,
      mockApiPlatformEventService,
      mockEmailConnector,
      mockTotpConnector,
      actorSystem,
      mockLockKeeper,
      ApiGatewayStoreMock.aMock,
      applicationResponseCreator,
      mockCredentialGenerator,
      ApiSubscriptionFieldsConnectorMock.aMock,
      mockThirdPartyDelegatedAuthorityConnector,
      mockNameValidationConfig,
      TokenServiceMock.aMock)

    when(mockCredentialGenerator.generate()).thenReturn("a" * 10)
    when(mockStateHistoryRepository.insert(*)).thenAnswer((s:StateHistory) =>successful(s))
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*, *, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*, *, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedNotification(*, *)(*)).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationDeletedNotification(*, *, *)(*)).thenReturn(successful(response))
    when(mockApiPlatformEventService.sendTeamMemberAddedEvent(any[ApplicationData], any[String], any[String])(any[HeaderCarrier])).thenReturn(successful(true))
    when(mockApiPlatformEventService.sendTeamMemberRemovedEvent(any[ApplicationData], any[String], any[String])(any[HeaderCarrier]))
      .thenReturn(successful(true))
    when(mockApiPlatformEventService.sendTeamMemberRemovedEvent(any[ApplicationData], any[String], any[String])(any[HeaderCarrier]))
      .thenReturn(successful(true))
    when(mockApiPlatformEventService.sendRedirectUrisUpdatedEvent(any[ApplicationData], any[String], any[String])(any[HeaderCarrier]))
      .thenReturn(successful(true))

    def mockSubscriptionRepositoryGetSubscriptionsToReturn(applicationId: UUID,
                                                           subscriptions: List[APIIdentifier]) =
      when(mockSubscriptionRepository.getSubscriptions(applicationId)).thenReturn(successful(subscriptions))

  }

  private def aSecret(secret: String): ClientSecret = ClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4))

  trait LockedSetup extends Setup {

    override lazy val locked = true
  }

  class MockLockKeeper(locked: Boolean) extends ApplicationLockKeeper(mock[ReactiveMongoComponent]) {

    override def repo: LockRepository = mock[LockRepository]

    override def lockId = ""

    var callsMadeToLockKeeper: Int = 0

    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
      callsMadeToLockKeeper = callsMadeToLockKeeper + 1
      if (locked) {
        successful(None)
      } else {
        successful(Some(Await.result(body, Duration(1, TimeUnit.SECONDS))))
      }
    }
  }

  override def beforeAll() {
    DateTimeUtils.setCurrentMillisFixed(DateTimeUtils.currentTimeMillis())
  }

  override def afterAll() {
    DateTimeUtils.setCurrentMillisSystem()
  }

  "Create" should {

    "create a new standard application in Mongo but not the API gateway for the PRINCIPAL (PRODUCTION) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApplicationRepoMock.Save.thenAnswer(successful)

      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Standard(), environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(createdApp.application.id, state = testingState(),
        environment = Environment.PRODUCTION)
      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, TESTING, Actor(loggedInUser, COLLABORATOR)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ),
        hc
      )
    }

    "create a new standard application in Mongo and the API gateway for the SUBORDINATE (SANDBOX) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Standard(), environment = Environment.SANDBOX)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(createdApp.application.id, state = ApplicationState(State.PRODUCTION),
        environment = Environment.SANDBOX)
      createdApp.totp shouldBe None

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, State.PRODUCTION, Actor(loggedInUser, COLLABORATOR)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ),
        hc
      )
    }

    "create a new Privileged application in Mongo and the API gateway with a Production state" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Privileged())
      
      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(applicationRequest.name)

      val prodTOTP = Totp("prodTotp", "prodTotpId")
      val totpQueue: mutable.Queue[Totp] = mutable.Queue(prodTOTP)
      when(mockTotpConnector.generateTotp()).thenAnswer(successful(totpQueue.dequeue()))

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id,
        state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser)),
        access = Privileged(totpIds = Some(TotpIds("prodTotpId")))
      )
      val expectedTotp = ApplicationTotps(prodTOTP)
      createdApp.totp shouldBe Some(TotpSecrets(expectedTotp.production.secret))

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, State.PRODUCTION, Actor("", GATEKEEPER)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ),
        hc
      )
    }

    "create a new ROPC application in Mongo and the API gateway with a Production state" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Ropc())

      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(applicationRequest.name)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id, state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser)), access = Ropc())
      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, State.PRODUCTION, Actor("", GATEKEEPER)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ),
        hc
      )
    }

    "fail with ApplicationAlreadyExists for privileged application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(Privileged())

      ApplicationRepoMock.FetchByName.thenReturnWhen(applicationRequest.name)(anApplicationData(UUID.randomUUID()))
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()

      intercept[ApplicationAlreadyExists] {
        await(underTest.create(applicationRequest)(hc))
      }
      AuditServiceMock.Audit.verifyCalledWith(
        CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName,
        Map("applicationName" -> applicationRequest.name),
        hc
      )
    }

    "fail with ApplicationAlreadyExists for ropc application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(Ropc())

      ApplicationRepoMock.FetchByName.thenReturnWhen(applicationRequest.name)(anApplicationData(UUID.randomUUID()))
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()

      intercept[ApplicationAlreadyExists] {
        await(underTest.create(applicationRequest)(hc))
      }
      AuditServiceMock.Audit.verifyCalledWith(
        CreateRopcApplicationRequestDeniedDueToNonUniqueName,
        Map("applicationName" -> applicationRequest.name),
        hc
      )
    }

    //See https://wso2.org/jira/browse/CAPIMGT-1
    "not create the application when there is already an application being published" in new LockedSetup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest()

      intercept[TimeoutException] {
        await(underTest.create(applicationRequest))
      }

      mockLockKeeper.callsMadeToLockKeeper should be > 1
      ApiGatewayStoreMock.verifyZeroInteractions()
      ApplicationRepoMock.verifyZeroInteractions()
    }

    "delete application when failed to create app in the API gateway" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(environment = Environment.SANDBOX)

      private val exception = new scala.RuntimeException("failed to generate tokens")
      ApiGatewayStoreMock.CreateApplication.thenFail(exception)
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.create(applicationRequest)))
      ex.getMessage shouldBe exception.getMessage

      
      ApplicationRepoMock.Save.verifyNeverCalled()
      ApiGatewayStoreMock.DeleteApplication.verifyCalled()
    }

    "delete application when failed to create state history" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest()

      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
      when(mockStateHistoryRepository.insert(*)).thenReturn(failed(new RuntimeException("Expected test failure")))
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()

      intercept[RuntimeException](await(underTest.create(applicationRequest)))

      val dbApplication = ApplicationRepoMock.Save.verifyCalled()
      ApiGatewayStoreMock.DeleteApplication.verifyCalled()
      ApplicationRepoMock.Delete.verifyCalledWith(dbApplication.id)
    }
  }

  "recordApplicationUsage" should {
    "update the Application and return an ExtendedApplicationResponse" in new Setup {
      val subscriptions: List[APIIdentifier] = List(APIIdentifier("myContext", "myVersion"))
      ApplicationRepoMock.RecordApplicationUsage.thenReturnWhen(applicationId)(applicationData)
      mockSubscriptionRepositoryGetSubscriptionsToReturn(applicationId, subscriptions)

      val applicationResponse: ExtendedApplicationResponse = await(underTest.recordApplicationUsage(applicationId))

      applicationResponse.id shouldBe applicationId
      applicationResponse.subscriptions shouldBe subscriptions
    }
  }

  "recordServerTokenUsage" should {
    "update the Application and return an ExtendedApplicationResponse" in new Setup {
      val subscriptions: List[APIIdentifier] = List(APIIdentifier("myContext", "myVersion"))
      ApplicationRepoMock.RecordServerTokenUsage.thenReturnWhen(applicationId)(applicationData)
      mockSubscriptionRepositoryGetSubscriptionsToReturn(applicationId, subscriptions)

      val applicationResponse: ExtendedApplicationResponse = await(underTest.recordServerTokenUsage(applicationId))

      applicationResponse.id shouldBe applicationId
      applicationResponse.subscriptions shouldBe subscriptions
      ApplicationRepoMock.RecordServerTokenUsage.verifyCalledWith(applicationId)
    }
  }

  "Update" should {
    val applicationRequest = anExistingApplicationRequest()

    "update an existing application if an id is provided" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(applicationData)

      await(underTest.update(applicationId, applicationRequest))

      ApplicationRepoMock.Save.verifyCalled()
    }

    "update an existing application if an id is provided and name is changed" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(applicationData)

      await(underTest.update(applicationId, applicationRequest))

      ApplicationRepoMock.Save.verifyCalled()
    }

    "throw a NotFoundException if application doesn't exist in repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.update(applicationId, applicationRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw a ForbiddenException when trying to change the access type of an application" in new Setup {

      val privilegedApplicationRequest: CreateApplicationRequest = applicationRequest.copy(access = Privileged())
      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[ForbiddenException](await(underTest.update(applicationId, privilegedApplicationRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }
  }

  "update approval" should {
    val approvalInformation = CheckInformation(Some(ContactDetails("Tester", "test@example.com", "12345677890")))

    "update an existing application if an id is provided" in new Setup {
      val applicationDataWithApproval = applicationData.copy(checkInformation = Some(approvalInformation))
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(applicationData)

      await(underTest.updateCheck(applicationId, approvalInformation))

      ApplicationRepoMock.Save.verifyCalledWith(applicationDataWithApproval)
    }

    "throw a NotFoundException if application doesn't exist in repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.updateCheck(applicationId, approvalInformation)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }
  }

  "fetch application" should {

    "return none when no application exists in the repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      val result = await(underTest.fetch(applicationId).value)

      result shouldBe None
    }

    "return an application when it exists in the repository for the given application id" in new Setup {
      val data: ApplicationData = anApplicationData(applicationId, rateLimitTier = Some(SILVER))

      ApplicationRepoMock.Fetch.thenReturn(data)

      val result = await(underTest.fetch(applicationId).value)

      result shouldBe Some(ApplicationResponse(
        id = applicationId,
        clientId = productionToken.clientId,
        gatewayId = data.wso2ApplicationName,
        name = data.name,
        deployedTo = data.environment,
        description = data.description,
        collaborators = data.collaborators,
        createdOn = data.createdOn,
        lastAccess = data.lastAccess,
        lastAccessTokenUsage = productionToken.lastAccessTokenUsage,
        redirectUris = List.empty,
        termsAndConditionsUrl = None,
        privacyPolicyUrl = None,
        access = data.access,
        state = data.state,
        rateLimitTier = SILVER))
    }

    "send an audit event for each type of change" in new Setup {
      val admin = Collaborator("test@example.com", ADMINISTRATOR)
      val tokens = ApplicationTokens(
        EnvironmentToken("prodId", "prodToken")
      )

      val existingApplication = ApplicationData(
        id = applicationId,
        name = "app name",
        normalisedName = "app name",
        collaborators = Set(admin),
        wso2ApplicationName = "wso2ApplicationName",
        tokens = tokens,
        state = testingState(),
        createdOn = HmrcTime.now,
        lastAccess = Some(HmrcTime.now)
      )
      val newRedirectUris =   List("http://new-url.example.com")
      val updatedApplication: ApplicationData = existingApplication.copy(
        name = "new name",
        normalisedName = "new name",
        access = Standard(
          newRedirectUris,
          Some("http://new-url.example.com/terms-and-conditions"),
          Some("http://new-url.example.com/privacy-policy"))
      )

      ApplicationRepoMock.Fetch.thenReturn(existingApplication)
      ApplicationRepoMock.Save.thenReturn(updatedApplication)

      await(underTest.update(applicationId, UpdateApplicationRequest(updatedApplication.name)))

      AuditServiceMock.verify.audit(eqTo(AppNameChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppTermsAndConditionsUrlChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppRedirectUrisChanged), *)(*)
      verify(mockApiPlatformEventService).sendRedirectUrisUpdatedEvent(eqTo(updatedApplication), eqTo(""), eqTo(newRedirectUris.mkString(",")))(any[HeaderCarrier])
      AuditServiceMock.verify.audit(eqTo(AppPrivacyPolicyUrlChanged), *)(*)
    }
  }

  "add collaborator" should {
    val admin: String = "admin@example.com"
    val admin2: String = "admin2@example.com"
    val email: String = "test@example.com"
    val adminsToEmail = Set(admin2)

    def collaboratorRequest(admin: String = admin,
                            email: String = email,
                            role: Role = DEVELOPER,
                            isRegistered: Boolean = false,
                            adminsToEmail: Set[String] = adminsToEmail) = {
      AddCollaboratorRequest(admin, Collaborator(email, role), isRegistered, adminsToEmail)
    }

    val request = collaboratorRequest()

    "throw notFoundException if no application exists in the repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.addCollaborator(applicationId, request)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "update collaborators when application exists in the repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      val expected = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)
      ApplicationRepoMock.Save.thenReturn(expected)

      private val addRequest = collaboratorRequest(isRegistered = true)
      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, addRequest))

      ApplicationRepoMock.Save.verifyCalledWith(expected)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorAdded,
        AuditHelper.applicationId(applicationId) ++ CollaboratorAdded.details(addRequest.collaborator),
        hc
      )
      result shouldBe AddCollaboratorResponse(registeredUser = true)

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData),
        eqTo(request.collaborator.emailAddress),
        eqTo(request.collaborator.role.toString()))(any[HeaderCarrier])
    }

    "send confirmation and notification emails to the developer and all relevant administrators when adding a registered collaborator" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator(admin2, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest(isRegistered = true)))

      ApplicationRepoMock.Save.verifyCalledWith(expected)

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData),
        eqTo(request.collaborator.emailAddress),
        eqTo(request.collaborator.role.toString()))(any[HeaderCarrier])
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorConfirmation("developer", applicationData.name, Set(email))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorNotification(email, "developer", applicationData.name, adminsToEmail)
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "send confirmation and notification emails to the developer and all relevant administrators when adding an unregistered collaborator" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator(admin2, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest()))

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData),
        eqTo(request.collaborator.emailAddress),
        eqTo(request.collaborator.role.toString()))(any[HeaderCarrier])
      ApplicationRepoMock.Save.verifyCalledWith(expected)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorConfirmation("developer", applicationData.name, Set(email))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorNotification(email, "developer", applicationData.name, adminsToEmail)
      result shouldBe AddCollaboratorResponse(registeredUser = false)
    }

    "send email confirmation to the developer and no notifications when there are no admins to email" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val admin = "theonlyadmin@example.com"
      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      val result: AddCollaboratorResponse =
        await(underTest.addCollaborator(applicationId, collaboratorRequest(admin = admin, isRegistered = true, adminsToEmail = Set.empty[String])))

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData),
        eqTo(request.collaborator.emailAddress),
        eqTo(request.collaborator.role.toString()))(any[HeaderCarrier])


      ApplicationRepoMock.Save.verifyCalledWith(expected)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorConfirmation("developer", applicationData.name, Set(email))
      verifyNoMoreInteractions(mockEmailConnector)
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "handle an unexpected failure when sending confirmation email" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      when(mockEmailConnector.sendAddedCollaboratorConfirmation(*, *, *)(*)).thenReturn(failed(new RuntimeException))

      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest(isRegistered = true)))

      ApplicationRepoMock.Save.verifyCalledWith(expected)
      verify(mockEmailConnector).sendAddedCollaboratorConfirmation(*, *, *)(*)
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "throw UserAlreadyPresent error when adding an existing collaborator with the same role" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[UserAlreadyExists](await(underTest.addCollaborator(applicationId, collaboratorRequest(email = loggedInUser, role = ADMINISTRATOR))))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw UserAlreadyPresent error when adding an existing collaborator with different role" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[UserAlreadyExists](await(underTest.addCollaborator(applicationId, collaboratorRequest(email = loggedInUser, role = DEVELOPER))))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw UserAlreadyPresent error when adding an existing collaborator with different case" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[UserAlreadyExists](await(underTest.addCollaborator(applicationId, collaboratorRequest(email = loggedInUser.toUpperCase, role = DEVELOPER))))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }
  }

  "delete collaborator" should {
    trait DeleteCollaboratorsSetup extends Setup {
      val admin = "admin@example.com"
      val admin2: String = "admin2@example.com"
      val collaborator = "test@example.com"
      val adminsToEmail = Set(admin2)
      val notifyCollaborator = true
      val collaborators = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator(admin2, ADMINISTRATOR),
        Collaborator(collaborator, DEVELOPER))
      override val applicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val updatedData = applicationData.copy(collaborators = applicationData.collaborators - Collaborator(collaborator, DEVELOPER))
    }


    "throw not found exception when no application exists in the repository for the given application id" in new DeleteCollaboratorsSetup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.deleteCollaborator(applicationId, collaborator, adminsToEmail, notifyCollaborator)))
      ApplicationRepoMock.Save.verifyNeverCalled()
      verifyZeroInteractions(mockEmailConnector)
    }

    "remove collaborator and send confirmation and notification emails" in new DeleteCollaboratorsSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedData)

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, collaborator, adminsToEmail, notifyCollaborator))

      ApplicationRepoMock.Save.verifyCalledWith(updatedData)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorRemoved,
        AuditHelper.applicationId(applicationId) ++ CollaboratorRemoved.details(Collaborator(collaborator, DEVELOPER)),
        hc
      )
      verify(mockApiPlatformEventService).sendTeamMemberRemovedEvent(eqTo(applicationData),
        eqTo(collaborator),
        eqTo("DEVELOPER"))(any[HeaderCarrier])
      result shouldBe updatedData.collaborators
    }

    "not send confirmation email to collaborator when notifyCollaborator is set to false" in new DeleteCollaboratorsSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedData)

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, collaborator, adminsToEmail, notifyCollaborator = false))

      ApplicationRepoMock.Save.verifyCalledWith(updatedData)
      verify(mockEmailConnector, never).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorRemoved,
        AuditHelper.applicationId(applicationId) ++ CollaboratorRemoved.details(Collaborator(collaborator, DEVELOPER)),
        hc
      )
      verify(mockApiPlatformEventService).sendTeamMemberRemovedEvent(eqTo(applicationData),
        eqTo(collaborator),
        eqTo("DEVELOPER"))(any[HeaderCarrier])
      result shouldBe updatedData.collaborators
    }

    "remove collaborator with email address in different case" in new DeleteCollaboratorsSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedData)

      AuditServiceMock.Audit.thenReturnSuccess()

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, collaborator.toUpperCase, adminsToEmail, notifyCollaborator))

      ApplicationRepoMock.Save.verifyCalledWith(updatedData)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      result shouldBe updatedData.collaborators

      verify(mockApiPlatformEventService).sendTeamMemberRemovedEvent(eqTo(applicationData),
        eqTo(collaborator),
        eqTo("DEVELOPER"))(any[HeaderCarrier])

    }

    "fail to delete last remaining admin user" in new DeleteCollaboratorsSetup {
      val onlyOneAdmin: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator(collaborator, DEVELOPER))
      val applicationWithOneAdmin: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = onlyOneAdmin)

      ApplicationRepoMock.Fetch.thenReturn(applicationWithOneAdmin)

      intercept[ApplicationNeedsAdmin](await(underTest.deleteCollaborator(applicationId, admin, adminsToEmail, notifyCollaborator)))

      ApplicationRepoMock.Save.verifyNeverCalled()
      verifyZeroInteractions(mockEmailConnector)
      verifyZeroInteractions(mockApiPlatformEventService)
    }
  }

  "fetchByClientId" should {

    "return none when no application exists in the repository for the given client id" in new Setup {
      val clientId = "some-client-id"
      ApplicationRepoMock.FetchByClientId.thenReturnNone()

      val result: Option[ApplicationResponse] = await(underTest.fetchByClientId(clientId))

      result shouldBe None
    }

    "return an application when it exists in the repository for the given client id" in new Setup {
      ApplicationRepoMock.FetchByClientId.thenReturnWhen(applicationData.tokens.production.clientId)(applicationData)

      val result: Option[ApplicationResponse] = await(underTest.fetchByClientId(applicationData.tokens.production.clientId))

      result.get.id shouldBe applicationId
      result.get.deployedTo shouldBe "PRODUCTION"
      result.get.collaborators shouldBe applicationData.collaborators
      result.get.createdOn shouldBe applicationData.createdOn
    }

  }

  "fetchByServerToken" should {

    val serverToken = "b3c83934c02df8b111e7f9f8700000"

    "return none when no application exists in the repository for the given server token" in new Setup {
      ApplicationRepoMock.FetchByServerToken.thenReturnNoneWhen(serverToken)

      val result: Option[ApplicationResponse] = await(underTest.fetchByServerToken(serverToken))

      result shouldBe None
    }

    "return an application when it exists in the repository for the given server token" in new Setup {

      val productionToken = EnvironmentToken("aaa", serverToken, List(aSecret("secret1"), aSecret("secret2")))

      override val applicationData: ApplicationData = anApplicationData(applicationId).copy(tokens = ApplicationTokens(productionToken))

      ApplicationRepoMock.FetchByServerToken.thenReturnWhen(serverToken)(applicationData)

      val result: Option[ApplicationResponse] = await(underTest.fetchByServerToken(serverToken))

      result.get.id shouldBe applicationId
      result.get.collaborators shouldBe applicationData.collaborators
      result.get.createdOn shouldBe applicationData.createdOn
    }
  }

  "fetchAllForCollaborator" should {

    "fetch all applications for a given collaborator email address" in new Setup {
      val emailAddress = "user@example.com"
      val standardApplicationData: ApplicationData = anApplicationData(applicationId, access = Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(applicationId, access = Privileged())
      val ropcApplicationData: ApplicationData = anApplicationData(applicationId, access = Ropc())

      ApplicationRepoMock.FetchAllForEmail.thenReturnWhen(emailAddress)(standardApplicationData, privilegedApplicationData, ropcApplicationData)

      await(underTest.fetchAllForCollaborator(emailAddress)).size shouldBe 3
    }

  }

  "fetchAllBySubscription" should {

    "return applications for a given subscription to an API context" in new Setup {
      val apiContext = "some-context"

      ApplicationRepoMock.FetchAllForContent.thenReturnWhen(apiContext)(applicationData)
      val result: List[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiContext))

      result.size shouldBe 1
      result shouldBe Seq(applicationData).map(app => ApplicationResponse(data = app))
    }

    "return no matching applications for a given subscription to an API context" in new Setup {
      val apiContext = "some-context"

      ApplicationRepoMock.FetchAllForContent.thenReturnEmptyWhen(apiContext)
      val result: Seq[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiContext))

      result.size shouldBe 0
    }

    "return applications for a given subscription to an API identifier" in new Setup {
      val apiIdentifier = APIIdentifier("some-context", "some-version")

      ApplicationRepoMock.FetchAllForApiIdentifier.thenReturnWhen(apiIdentifier)(applicationData)
      val result: List[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiIdentifier))

      result.size shouldBe 1
      result shouldBe Seq(applicationData).map(app => ApplicationResponse(data = app))
    }

    "return no matching applications for a given subscription to an API identifier" in new Setup {
      val apiIdentifier = APIIdentifier("some-context", "some-version")

      ApplicationRepoMock.FetchAllForApiIdentifier.thenReturnEmptyWhen(apiIdentifier)

      val result: Seq[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiIdentifier))

      result.size shouldBe 0
    }
  }

  "fetchAllWithNoSubscriptions" should {

    "return no matching applications if application has a subscription" in new Setup {
      ApplicationRepoMock.FetchAllWithNoSubscriptions.thenReturnNone()

      val result: Seq[ApplicationResponse] = await(underTest.fetchAllWithNoSubscriptions())

      result.size shouldBe 0
    }

    "return applications when there are no matching subscriptions" in new Setup {
      ApplicationRepoMock.FetchAllWithNoSubscriptions.thenReturn(applicationData)

      val result: List[ApplicationResponse] = await(underTest.fetchAllWithNoSubscriptions())

      result.size shouldBe 1
      result shouldBe List(applicationData).map(app => ApplicationResponse(data = app))
    }
  }

  "verifyUplift" should {
    val upliftRequestedBy = "email@example.com"

    "update the state of the application and create app in the API gateway when application is in pendingRequesterVerification state" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenReturn(mock[ApplicationData])

      val expectedStateHistory = StateHistory(applicationId, State.PRODUCTION, Actor(upliftRequestedBy, COLLABORATOR), Some(PENDING_REQUESTER_VERIFICATION))
      val upliftRequest = StateHistory(applicationId, PENDING_GATEKEEPER_APPROVAL, Actor(upliftRequestedBy, COLLABORATOR), Some(TESTING))

      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      val expectedApplication: ApplicationData = application.copy(state = productionState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)
      when(mockStateHistoryRepository.fetchLatestByStateForApplication(applicationId, PENDING_GATEKEEPER_APPROVAL)).thenReturn(successful(Some(upliftRequest)))

      val result: ApplicationStateChange = await(underTest.verifyUplift(generatedVerificationCode))
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplication)
      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      result shouldBe UpliftVerified
      verify(mockStateHistoryRepository).insert(expectedStateHistory)
    }

    "fail if the application save fails" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))
      val saveException = new RuntimeException("application failed to save")

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)
      ApplicationRepoMock.Save.thenFail(saveException)

      intercept[RuntimeException] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "rollback if saving the state history fails" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))
      ApplicationRepoMock.Save.thenReturn(mock[ApplicationData])
      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)
      when(mockStateHistoryRepository.insert(*)).thenReturn(failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }

      ApplicationRepoMock.Save.verifyCalledWith(application)
    }

    "not update the state but result in success of the application when application is already in production state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, productionState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)

      val result: ApplicationStateChange = await(underTest.verifyUplift(generatedVerificationCode))
      result shouldBe UpliftVerified
      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "fail when application is in testing state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "fail when application is in pendingGatekeeperApproval state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "fail when application is not found by verification code" in new Setup {
      anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnNoneWhen(generatedVerificationCode)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }
  }

  "validate application name" should {

    "allow valid name" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("HMRC"))

      val result = await(underTest.validateApplicationName("my application name", None))

      result shouldBe Valid
    }

    "block a name with HMRC in" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("HMRC"))

      val result = await(underTest.validateApplicationName("Invalid name HMRC", None))

      result shouldBe Invalid.invalidName
    }

    "block a name with multiple blacklisted names in" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("InvalidName1", "InvalidName2", "InvalidName3"))

      val result = await(underTest.validateApplicationName("ValidName InvalidName1 InvalidName2", None))

      result shouldBe Invalid.invalidName
    }

    "block an invalid ignoring case" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("InvalidName"))

      val result = await(underTest.validateApplicationName("invalidname", None))

      result shouldBe Invalid.invalidName
    }

    "block a duplicate app name" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = UUID.randomUUID()))

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List.empty[String])

      private val duplicateName = "duplicate name"
      val result = await(underTest.validateApplicationName(duplicateName, None))

      result shouldBe Invalid.duplicateName

      ApplicationRepoMock.FetchByName.verifyCalledWith(duplicateName)
    }

    "Ignore duplicate name check if not configured e.g. on a subordinate / sandbox environment" in new Setup {
      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List.empty[String])

      when(mockNameValidationConfig.validateForDuplicateAppNames)
        .thenReturn(false)

      val result = await(underTest.validateApplicationName("app name", None))

      result shouldBe Valid

      ApplicationRepoMock.FetchByName.veryNeverCalled()
    }

    "Ignore application when checking for duplicates if it is self application" in new Setup {
      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List.empty)

      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = applicationId))

      val result = await(underTest.validateApplicationName("app name", Some(applicationId)))

      result shouldBe Valid
    }
  }

  "requestUplift" should {
    val requestedName = "application name"
    val upliftRequestedBy = "email@example.com"

    "update the state of the application" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer(successful)

      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val expectedApplication: ApplicationData = application.copy(state = pendingGatekeeperApprovalState(upliftRequestedBy),
        name = requestedName, normalisedName = requestedName.toLowerCase)

      val expectedStateHistory = StateHistory(applicationId = expectedApplication.id, state = PENDING_GATEKEEPER_APPROVAL,
        actor = Actor(upliftRequestedBy, COLLABORATOR), previousState = Some(TESTING))

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.FetchByName.thenReturnWhen(requestedName)(application,expectedApplication)

      val result: ApplicationStateChange = await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))

      ApplicationRepoMock.Save.verifyCalledWith(expectedApplication)
      result shouldBe UpliftRequested
      verify(mockStateHistoryRepository).insert(expectedStateHistory)
    }

    "rollback the application when storing the state history fails" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApplicationRepoMock.FetchByName.thenReturnWhen(requestedName)(application)

      when(mockStateHistoryRepository.insert(*))
        .thenReturn(failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }

      ApplicationRepoMock.Save.verifyCalledWith(application)
    }

    "send an Audit event when an application uplift is successfully requested with no name change" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      await(underTest.requestUplift(applicationId, application.name, upliftRequestedBy))
      AuditServiceMock.Audit.verifyCalledWith(ApplicationUpliftRequested, Map("applicationId" -> application.id.toString), hc)
    }

    "send an Audit event when an application uplift is successfully requested with a name change" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(requestedName)

      await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))

      val expectedAuditDetails: Map[String, String] = Map("applicationId" -> application.id.toString, "newApplicationName" -> requestedName)
      AuditServiceMock.Audit.verifyCalledWith(ApplicationUpliftRequested, expectedAuditDetails, hc)
    }

    "fail with InvalidStateTransition without invoking fetchNonTestingApplicationByName when the application is not in testing" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState("test@example.com"))

      ApplicationRepoMock.Fetch.thenReturn(application)

      intercept[InvalidStateTransition] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
      ApplicationRepoMock.FetchByName.veryNeverCalled()
    }

    "fail with ApplicationAlreadyExists when another uplifted application already exist with the same name" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val anotherApplication: ApplicationData = anApplicationData(UUID.randomUUID(), productionState("admin@example.com"))

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.FetchByName.thenReturnWhen(requestedName)(application,anotherApplication)

      intercept[ApplicationAlreadyExists] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
    }

    "propagate the exception when the repository fail" in new Setup {
      ApplicationRepoMock.Fetch.thenFail(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
    }
  }

  "update rate limit tier" should {

    val uuid: UUID = UUID.randomUUID()
    val originalApplicationData: ApplicationData = anApplicationData(uuid)
    val updatedApplicationData: ApplicationData = originalApplicationData copy (rateLimitTier = Some(SILVER))

    "update the application on AWS and in mongo" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(originalApplicationData)
      ApiGatewayStoreMock.UpdateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.UpdateApplicationRateLimit.thenReturn(uuid, SILVER)(updatedApplicationData)

      await(underTest updateRateLimitTier(uuid, SILVER))

      ApiGatewayStoreMock.UpdateApplication.verifyCalledWith(originalApplicationData, SILVER)
      ApplicationRepoMock.UpdateApplicationRateLimit.verifyCalledWith(uuid, SILVER)
    }

  }

  "update IP whitelist" should {
    "update the IP whitelist in the application in Mongo" in new Setup {
      val newIpWhitelist: Set[String] = Set("192.168.100.0/22", "192.168.104.1/32")
      val updatedApplicationData: ApplicationData = anApplicationData(applicationId, ipWhitelist = newIpWhitelist)
      ApplicationRepoMock.UpdateIpWhitelist.thenReturnWhen(applicationId, newIpWhitelist)(updatedApplicationData)

      val result: ApplicationData = await(underTest.updateIpWhitelist(applicationId, newIpWhitelist))

      result shouldBe updatedApplicationData
      ApplicationRepoMock.UpdateIpWhitelist.verifyCalledWith(applicationId, newIpWhitelist)
    }

    "fail when the IP address is out of range" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpWhitelist(UUID.randomUUID(), Set("392.168.100.0/22")))
      }

      error.getMessage shouldBe "Value [392] not in range [0,255]"
    }

    "fail when the mask is out of range" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpWhitelist(UUID.randomUUID(), Set("192.168.100.0/55")))
      }

      error.getMessage shouldBe "Value [55] not in range [0,32]"
    }

    "fail when the format is invalid" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpWhitelist(UUID.randomUUID(), Set("192.100.0/22")))
      }

      error.getMessage shouldBe "Could not parse [192.100.0/22]"
    }
  }

  "update IP allowlist" should {
    "update the IP allowlist in the application in Mongo" in new Setup {
      val newIpAllowlist: IpAllowlist = IpAllowlist(required = true, Set("192.168.100.0/22", "192.168.104.1/32"))
      val updatedApplicationData: ApplicationData = anApplicationData(applicationId, ipAllowlist = newIpAllowlist)
      ApplicationRepoMock.UpdateIpAllowlist.thenReturnWhen(applicationId, newIpAllowlist)(updatedApplicationData)

      val result: ApplicationData = await(underTest.updateIpAllowlist(applicationId, newIpAllowlist))

      result shouldBe updatedApplicationData
      ApplicationRepoMock.UpdateIpAllowlist.verifyCalledWith(applicationId, newIpAllowlist)
    }

    "fail when the IP address is out of range" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpAllowlist(UUID.randomUUID(), IpAllowlist(required = true, Set("392.168.100.0/22"))))
      }

      error.getMessage shouldBe "Value [392] not in range [0,255]"
    }

    "fail when the mask is out of range" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpAllowlist(UUID.randomUUID(), IpAllowlist(required = true, Set("192.168.100.0/55"))))
      }

      error.getMessage shouldBe "Value [55] not in range [0,32]"
    }

    "fail when the format is invalid" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpAllowlist(UUID.randomUUID(), IpAllowlist(required = true, Set("192.100.0/22"))))
      }

      error.getMessage shouldBe "Could not parse [192.100.0/22]"
    }
  }

  "deleting an application" should {

    trait DeleteApplicationSetup extends Setup {
      val deleteRequestedBy = "email@example.com"
      val gatekeeperUserId = "big.boss.gatekeeper"
      val request = DeleteApplicationRequest(gatekeeperUserId,deleteRequestedBy)
      val api1 = APIIdentifier("hello", "1.0")
      val api2 = APIIdentifier("goodbye", "1.0")

      type T = ApplicationData => Future[AuditResult]
      val mockAuditResult = mock[Future[AuditResult]]
      val auditFunction: T = mock[T]

      when(auditFunction.apply(*)).thenReturn(mockAuditResult)

      when(mockSubscriptionRepository.getSubscriptions(applicationId)).thenReturn(successful(List(api1, api2)))
      when(mockSubscriptionRepository.remove(*, *)).thenReturn(successful(HasSucceeded))

      when(mockStateHistoryRepository.deleteByApplicationId(*)).thenReturn(successful(HasSucceeded))

      when(mockThirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(*)(*)).thenReturn(successful(HasSucceeded))

      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
    }

    "return a state change to indicate that the application has been deleted" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      val result = await(underTest.deleteApplication(applicationId, Some(request), auditFunction))
      result shouldBe Deleted
    }

    "call to ApiGatewayStore to delete the application" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApiGatewayStoreMock.DeleteApplication.verifyCalledWith(applicationData)
    }

    "call to the API Subscription Fields service to delete subscription field data" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.verifyCalledWith(applicationData.tokens.production.clientId)
    }

    "delete the application subscriptions from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(mockSubscriptionRepository).remove(eqTo(applicationId), eqTo(api1))
      verify(mockSubscriptionRepository).remove(eqTo(applicationId), eqTo(api2))
    }

    "delete the application from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApplicationRepoMock.Delete.verifyCalledWith(applicationId)
    }

    "delete the application state history from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(mockStateHistoryRepository).deleteByApplicationId(applicationId)
    }

    "audit the application deletion" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      when(auditFunction.apply(any[ApplicationData])).thenReturn(Future.successful(mock[AuditResult]))

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(auditFunction).apply(eqTo(applicationData))
    }

    "audit the application when the deletion has not worked" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      when(auditFunction.apply(any[ApplicationData])).thenReturn(Future.failed(new RuntimeException))

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(auditFunction).apply(eqTo(applicationData))
    }

    "send the application deleted notification email" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Delete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(mockEmailConnector).sendApplicationDeletedNotification(
        applicationData.name, deleteRequestedBy, applicationData.admins.map(_.emailAddress))
    }

    "silently ignore the delete request if no application exists for the application id (to ensure idempotency)" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction)) shouldBe Deleted

      ApplicationRepoMock.Fetch.verifyCalledWith(applicationId)
      verifyNoMoreInteractions(ApiGatewayStoreMock.aMock, ApplicationRepoMock.aMock, mockStateHistoryRepository,
        mockSubscriptionRepository, AuditServiceMock.aMock, mockEmailConnector, ApiSubscriptionFieldsConnectorMock.aMock)
    }
  }

  "Search" should {
    "return results based on provided ApplicationSearch" in new Setup {
      val standardApplicationData: ApplicationData = anApplicationData(UUID.randomUUID(), access = Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(UUID.randomUUID(), access = Privileged())
      val ropcApplicationData: ApplicationData = anApplicationData(UUID.randomUUID(), access = Ropc())

      val search = ApplicationSearch(
        pageNumber = 2,
        pageSize = 5
      )

      ApplicationRepoMock.SearchApplications.thenReturn(
        PaginatedApplicationData(
          List(
            standardApplicationData,
            privilegedApplicationData,
            ropcApplicationData
          ),
          List(
            PaginationTotal(3)
          ),
          List(
            PaginationTotal(3)
          )))

      val result: PaginatedApplicationResponse = await(underTest.searchApplications(search))

      result.total shouldBe 3
      result.matching shouldBe 3
      result.applications.size shouldBe 3
    }
  }

  private def aNewApplicationRequest(access: Access = Standard(), environment: Environment = Environment.PRODUCTION) = {
    CreateApplicationRequest("MyApp", access, Some("description"), environment,
      Set(Collaborator(loggedInUser, ADMINISTRATOR)))
  }

  private def anExistingApplicationRequest() = {
    CreateApplicationRequest(
      "My Application",
      access = Standard(
        redirectUris = List("http://example.com/redirect"),
        termsAndConditionsUrl = Some("http://example.com/terms"),
        privacyPolicyUrl = Some("http://example.com/privacy"),
        overrides = Set.empty
      ),
      Some("Description"),
      environment = Environment.PRODUCTION,
      Set(
        Collaborator(loggedInUser, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER)))
  }

  private val requestedByEmail = "john.smith@example.com"

  private def anApplicationData(applicationId: UUID,
                                state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR)),
                                access: Access = Standard(),
                                rateLimitTier: Option[RateLimitTier] = Some(RateLimitTier.BRONZE),
                                environment: Environment = Environment.PRODUCTION,
                                ipWhitelist: Set[String] = Set.empty,
                                ipAllowlist: IpAllowlist = IpAllowlist()) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      state,
      access,
      HmrcTime.now,
      Some(HmrcTime.now),
      rateLimitTier = rateLimitTier,
      environment = environment.toString,
      ipWhitelist = ipWhitelist,
      ipAllowlist = ipAllowlist)
  }
}
