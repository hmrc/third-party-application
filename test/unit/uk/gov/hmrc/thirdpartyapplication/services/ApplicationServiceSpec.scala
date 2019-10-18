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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID
import java.util.concurrent.{TimeUnit, TimeoutException}

import akka.actor.ActorSystem
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.DateTimeUtils
import org.mockito.BDDMockito.given
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.{Answer, OngoingStubbing}
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.{ForbiddenException, HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.{EmailConnector, TotpConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.{AddCollaboratorRequest, AddCollaboratorResponse}
import uk.gov.hmrc.thirdpartyapplication.models.ActorType.{COLLABORATOR, GATEKEEPER}
import uk.gov.hmrc.thirdpartyapplication.models.Environment.{Environment, PRODUCTION}
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{RateLimitTier, SILVER}
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models.State._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class ApplicationServiceSpec extends UnitSpec with ScalaFutures with MockitoSugar with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup {

    val actorSystem: ActorSystem = ActorSystem("System")

    lazy val locked = false
    protected val mockitoTimeout = 1000
    val mockApiGatewayStore: ApiGatewayStore = mock[ApiGatewayStore]
    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
    val mockSubscriptionRepository: SubscriptionRepository = mock[SubscriptionRepository]
    val mockStateHistoryRepository: StateHistoryRepository = mock[StateHistoryRepository]
    val mockAuditService: AuditService = mock[AuditService]
    val mockEmailConnector: EmailConnector = mock[EmailConnector]
    val mockTotpConnector: TotpConnector = mock[TotpConnector]
    val mockLockKeeper = new MockLockKeeper(locked)
    val response: HttpResponse = mock[HttpResponse]
    val trustedApplicationId1: UUID = UUID.fromString("162017dc-607b-4405-8208-a28308672f76")
    val trustedApplicationId2: UUID = UUID.fromString("162017dc-607b-4405-8208-a28308672f77")

    val mockTrustedApplications: TrustedApplications = mock[TrustedApplications]
    when(mockTrustedApplications.isTrusted(any[ApplicationData]())).thenReturn(false)
    when(mockTrustedApplications.isTrusted(anApplicationData(trustedApplicationId1))).thenReturn(true)
    when(mockTrustedApplications.isTrusted(anApplicationData(trustedApplicationId2))).thenReturn(true)

    when(mockApplicationRepository.fetchApplicationByName(any()))
      .thenReturn(Future.successful(None))

    val applicationResponseCreator = new ApplicationResponseCreator(mockTrustedApplications)

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser,
      LOGGED_IN_USER_NAME_HEADER -> "John Smith"
    )

    val mockCredentialGenerator: CredentialGenerator = mock[CredentialGenerator]

    val mockNameValidationConfig = mock[ApplicationNameValidationConfig]

    val underTest = new ApplicationService(
      mockApplicationRepository,
      mockStateHistoryRepository,
      mockSubscriptionRepository,
      mockAuditService,
      mockEmailConnector,
      mockTotpConnector,
      actorSystem,
      mockLockKeeper,
      mockApiGatewayStore,
      applicationResponseCreator,
      mockCredentialGenerator,
      mockTrustedApplications,
      mockNameValidationConfig)

    when(mockCredentialGenerator.generate()).thenReturn("a" * 10)
    when(mockApiGatewayStore.createApplication(any(), any(), any())(any[HeaderCarrier]))
      .thenReturn(successful(productionToken))
    when(mockApplicationRepository.save(any())).thenAnswer(new Answer[Future[ApplicationData]] {
      override def answer(invocation: InvocationOnMock): Future[ApplicationData] = {
        successful(invocation.getArguments()(0).asInstanceOf[ApplicationData])
      }
    })
    when(mockStateHistoryRepository.insert(any())).thenAnswer(new Answer[Future[StateHistory]] {
      override def answer(invocation: InvocationOnMock): Future[StateHistory] = {
        successful(invocation.getArguments()(0).asInstanceOf[StateHistory])
      }
    })
    when(mockEmailConnector.sendRemovedCollaboratorNotification(anyString(), anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(anyString(), anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    when(mockEmailConnector.sendApplicationApprovedNotification(anyString(), any())(any[HeaderCarrier]())).thenReturn(successful(response))
    mockWso2ApiStoreUpdateApplicationToReturn(HasSucceeded)
    mockWso2SubscribeToReturn(HasSucceeded)

    def mockApplicationRepositoryFetchToReturn(uuid: UUID,
                                               eventualMaybeApplicationData: Future[Option[ApplicationData]]
                                              ): OngoingStubbing[Future[Option[ApplicationData]]] = {
      when(mockApplicationRepository fetch uuid) thenReturn eventualMaybeApplicationData
    }

    def mockWso2ApiStoreUpdateApplicationToReturn(eventualHasSucceeded: Future[HasSucceeded]): OngoingStubbing[Future[HasSucceeded]] = {
      when(mockApiGatewayStore.updateApplication(any[ApplicationData], any[RateLimitTier])(any[HeaderCarrier])) thenReturn eventualHasSucceeded
    }

    def mockWso2SubscribeToReturn(eventualHasSucceeded: Future[HasSucceeded]): OngoingStubbing[Future[HasSucceeded]] = {
      when(mockApiGatewayStore
        .resubscribeApi(any[Seq[APIIdentifier]], anyString(), anyString(), anyString(), any[APIIdentifier], any[RateLimitTier])(any[HeaderCarrier]))
        .thenReturn(eventualHasSucceeded)
    }

    def mockApplicationRepositorySaveToReturn(eventualApplicationData: Future[ApplicationData]): OngoingStubbing[Future[ApplicationData]] = {
      when(mockApplicationRepository save any[ApplicationData]) thenReturn eventualApplicationData
    }

    def mockSubscriptionRepositoryGetSubscriptionsToReturn(applicationId: UUID,
                                                           subscriptions: Seq[APIIdentifier]): OngoingStubbing[Future[Seq[APIIdentifier]]] =
      when(mockSubscriptionRepository.getSubscriptions(applicationId)).thenReturn(successful(subscriptions))

  }

  private def aSecret(secret: String): ClientSecret = {
    ClientSecret(secret, secret)
  }

  private val loggedInUser = "loggedin@example.com"
  private val productionToken = EnvironmentToken("aaa", "bbb", "wso2Secret", Seq(aSecret("secret1"), aSecret("secret2")))

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

    "create a new standard application in Mongo and WSO2 for the PRINCIPAL (PRODUCTION) environment" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Standard(), environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(createdApp.application.id, state = testingState(),
        environment = Environment.PRODUCTION)
      createdApp.totp shouldBe None
      verify(mockApiGatewayStore).createApplication(any(), any(), any())(any[HeaderCarrier])
      verify(mockApplicationRepository).save(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, TESTING, Actor(loggedInUser, COLLABORATOR)))
      verify(mockAuditService).audit(AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ))
    }

    "create a new standard application in Mongo and WSO2 for the SUBORDINATE (SANDBOX) environment" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Standard(), environment = Environment.SANDBOX)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(createdApp.application.id, state = ApplicationState(State.PRODUCTION),
        environment = Environment.SANDBOX)
      createdApp.totp shouldBe None
      verify(mockApiGatewayStore).createApplication(any(), any(), any())(any[HeaderCarrier])
      verify(mockApplicationRepository).save(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, State.PRODUCTION, Actor(loggedInUser, COLLABORATOR)))
      verify(mockAuditService).audit(AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ))
    }

    "create a new standard application in Mongo and WSO2" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Standard())

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(createdApp.application.id, state = testingState())
      createdApp.totp shouldBe None
      verify(mockApiGatewayStore).createApplication(any(), any(), any())(any[HeaderCarrier])
      verify(mockApplicationRepository).save(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, TESTING, Actor(loggedInUser, COLLABORATOR)))
      verify(mockAuditService).audit(AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ))
    }

    "create a new Privileged application in Mongo and WSO2 with a Production state" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Privileged())
      when(mockApplicationRepository.fetchApplicationByName(applicationRequest.name)).thenReturn(None)

      val prodTOTP = Totp("prodTotp", "prodTotpId")
      val sandboxTOTP = Totp("sandboxTotp", "sandboxTotpId")
      val totpQueue: mutable.Queue[Totp] = mutable.Queue(prodTOTP, sandboxTOTP)
      given(mockTotpConnector.generateTotp()).willAnswer(new Answer[Future[Totp]] {
        override def answer(invocationOnMock: InvocationOnMock): Future[Totp] = successful(totpQueue.dequeue())
      })

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id,
        state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser)),
        access = Privileged(totpIds = Some(TotpIds("prodTotpId", "sandboxTotpId")))
      )
      val expectedTotp = ApplicationTotps(prodTOTP, sandboxTOTP)
      createdApp.totp shouldBe Some(TotpSecrets(expectedTotp.production.secret, expectedTotp.sandbox.secret))

      verify(mockApiGatewayStore).createApplication(any(), any(), any())(any[HeaderCarrier])
      verify(mockApplicationRepository).save(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, State.PRODUCTION, Actor("", GATEKEEPER)))
      verify(mockAuditService).audit(AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ))
    }

    "create a new ROPC application in Mongo and WSO2 with a Production state" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(access = Ropc())

      when(mockApplicationRepository.fetchApplicationByName(applicationRequest.name)).thenReturn(None)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id, state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser)), access = Ropc())
      verify(mockApiGatewayStore).createApplication(any(), any(), any())(any[HeaderCarrier])
      verify(mockApplicationRepository).save(expectedApplicationData)
      verify(mockStateHistoryRepository).insert(StateHistory(createdApp.application.id, State.PRODUCTION, Actor("", GATEKEEPER)))
      verify(mockAuditService).audit(AppCreated,
        Map(
          "applicationId" -> createdApp.application.id.toString,
          "newApplicationName" -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ))
    }

    "fail with ApplicationAlreadyExists for privileged application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(Privileged())

      when(mockApplicationRepository.fetchApplicationByName(applicationRequest.name)).thenReturn(Some(anApplicationData(UUID.randomUUID())))

      intercept[ApplicationAlreadyExists] {
        await(underTest.create(applicationRequest)(hc))
      }
      verify(mockAuditService).audit(CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName,
        Map("applicationName" -> applicationRequest.name))
    }

    "fail with ApplicationAlreadyExists for ropc application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest(Ropc())

      when(mockApplicationRepository.fetchApplicationByName(applicationRequest.name)).thenReturn(Some(anApplicationData(UUID.randomUUID())))

      intercept[ApplicationAlreadyExists] {
        await(underTest.create(applicationRequest)(hc))
      }
      verify(mockAuditService).audit(CreateRopcApplicationRequestDeniedDueToNonUniqueName, Map("applicationName" -> applicationRequest.name))
    }

    //See https://wso2.org/jira/browse/CAPIMGT-1
    "not create the application when there is already an application being published" in new LockedSetup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest()

      intercept[TimeoutException] {
        await(underTest.create(applicationRequest))
      }

      mockLockKeeper.callsMadeToLockKeeper should be > 1
      verifyZeroInteractions(mockApiGatewayStore)
      verifyZeroInteractions(mockApplicationRepository)
    }

    "delete application when failed to create app and generate tokens" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest()
      val applicationData: ApplicationData = anApplicationData(UUID.randomUUID())

      private val exception = new scala.RuntimeException("failed to generate tokens")
      when(mockApiGatewayStore.createApplication(any(), any(), any())(any[HeaderCarrier])).thenReturn(failed(exception))
      when(mockApiGatewayStore.deleteApplication(anyString(), anyString(), anyString())(any[HeaderCarrier])).thenReturn(Future(HasSucceeded))

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.create(applicationRequest)))
      ex.getMessage shouldBe exception.getMessage

      verify(mockApplicationRepository, never()).save(any())
      verify(mockApiGatewayStore).deleteApplication(anyString(), anyString(), anyString())(any[HeaderCarrier])
    }

    "delete application when failed to create state history" in new Setup {
      val dbApplication: ArgumentCaptor[ApplicationData] = ArgumentCaptor.forClass(classOf[ApplicationData])
      val applicationRequest: CreateApplicationRequest = aNewApplicationRequest()

      when(mockStateHistoryRepository.insert(any())).thenReturn(failed(new RuntimeException("Expected test failure")))
      when(mockApiGatewayStore.deleteApplication(anyString(), anyString(), anyString())(any[HeaderCarrier])).thenReturn(Future(HasSucceeded))

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.create(applicationRequest)))

      verify(mockApplicationRepository).save(dbApplication.capture())
      verify(mockApiGatewayStore).deleteApplication(anyString(), anyString(), anyString())(any[HeaderCarrier])
      verify(mockApplicationRepository).delete(dbApplication.getValue.id)
    }
  }

  "recordApplicationUsage" should {
    "update the Application and return an ApplicationResponse" in new Setup {
      val applicationId: UUID = UUID.randomUUID()

      when(mockApplicationRepository.recordApplicationUsage(applicationId)).thenReturn(anApplicationData(applicationId))

      val applicationResponse: ApplicationResponse = await(underTest.recordApplicationUsage(applicationId))

      applicationResponse.id shouldBe applicationId
    }
  }

  "Update" should {
    val id = UUID.randomUUID()
    val applicationRequest = anExistingApplicationRequest()
    val applicationData = anApplicationData(id)

    "update an existing application if an id is provided" in new Setup {

      mockApplicationRepositoryFetchToReturn(id, successful(Some(applicationData)))
      mockApplicationRepositorySaveToReturn(successful(applicationData))

      await(underTest.update(id, applicationRequest))

      verify(mockApplicationRepository).save(any())
    }

    "update an existing application if an id is provided and name is changed" in new Setup {

      mockApplicationRepositoryFetchToReturn(id, successful(Some(applicationData)))
      mockApplicationRepositorySaveToReturn(successful(applicationData))

      await(underTest.update(id, applicationRequest))

      verify(mockApplicationRepository).save(any())
    }

    "throw a NotFoundException if application doesn't exist in repository for the given application id" in new Setup {

      mockApplicationRepositoryFetchToReturn(id, successful(None))

      intercept[NotFoundException](await(underTest.update(id, applicationRequest)))

      verify(mockApplicationRepository, never()).save(any())
    }

    "throw a ForbiddenException when trying to change the access type of an application" in new Setup {

      val privilegedApplicationRequest: CreateApplicationRequest = applicationRequest.copy(access = Privileged())

      mockApplicationRepositoryFetchToReturn(id, successful(Some(applicationData)))

      intercept[ForbiddenException](await(underTest.update(id, privilegedApplicationRequest)))

      verify(mockApplicationRepository, never()).save(any())
    }
  }

  "update approval" should {
    val id = UUID.randomUUID()
    val approvalInformation = CheckInformation(Some(ContactDetails("Tester", "test@example.com", "12345677890")))
    val applicationData = anApplicationData(id)
    val applicationDataWithApproval = applicationData.copy(checkInformation = Some(approvalInformation))

    "update an existing application if an id is provided" in new Setup {

      mockApplicationRepositoryFetchToReturn(id, successful(Some(applicationData)))
      mockApplicationRepositorySaveToReturn(successful(applicationDataWithApproval))

      await(underTest.updateCheck(id, approvalInformation))

      verify(mockApplicationRepository).save(any())
    }

    "throw a NotFoundException if application doesn't exist in repository for the given application id" in new Setup {

      mockApplicationRepositoryFetchToReturn(id, successful(None))

      intercept[NotFoundException](await(underTest.updateCheck(id, approvalInformation)))

      verify(mockApplicationRepository, never()).save(any())
    }
  }

  "fetch application" should {
    val applicationId = UUID.randomUUID()

    "return none when no application exists in the repository for the given application id" in new Setup {
      mockApplicationRepositoryFetchToReturn(applicationId, None)

      val result: Option[ApplicationResponse] = await(underTest.fetch(applicationId))

      result shouldBe None
    }

    "return an application when it exists in the repository for the given application id" in new Setup {
      val data: ApplicationData = anApplicationData(applicationId, rateLimitTier = Some(SILVER))

      mockApplicationRepositoryFetchToReturn(applicationId, Some(data))

      val result: Option[ApplicationResponse] = await(underTest.fetch(applicationId))

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
        redirectUris = Seq.empty,
        termsAndConditionsUrl = None,
        privacyPolicyUrl = None,
        access = data.access,
        environment = Some(Environment.PRODUCTION),
        state = data.state,
        rateLimitTier = SILVER))
    }

    "return an application with trusted flag when the application is in the whitelist" in new Setup {

      val applicationData: ApplicationData = anApplicationData(trustedApplicationId2)

      when(mockApplicationRepository.fetch(trustedApplicationId2)).thenReturn(Some(applicationData))

      val result: Option[ApplicationResponse] = await(underTest.fetch(trustedApplicationId2))

      result.get.trusted shouldBe true
    }

    "send an audit event for each type of change" in new Setup {
      val admin = Collaborator("test@example.com", ADMINISTRATOR)
      val id: UUID = UUID.randomUUID()
      val tokens = ApplicationTokens(
        EnvironmentToken("prodId", "prodSecret", "prodToken")
      )

      val existingApplication = ApplicationData(
        id = id,
        name = "app name",
        normalisedName = "app name",
        collaborators = Set(admin),
        wso2Password = "wso2Password",
        wso2ApplicationName = "wso2ApplicationName",
        wso2Username = "wso2Username",
        tokens = tokens,
        state = testingState(),
        createdOn = HmrcTime.now,
        lastAccess = Some(HmrcTime.now)
      )

      val updatedApplication: ApplicationData = existingApplication.copy(
        name = "new name",
        normalisedName = "new name",
        access = Standard(
          Seq("http://new-url.example.com"),
          Some("http://new-url.example.com/terms-and-conditions"),
          Some("http://new-url.example.com/privacy-policy"))
      )

      mockApplicationRepositoryFetchToReturn(id, successful(Some(existingApplication)))
      mockApplicationRepositorySaveToReturn(successful(updatedApplication))

      await(underTest.update(id, UpdateApplicationRequest(updatedApplication.name)))

      verify(mockAuditService).audit(refEq(AppNameChanged), any[Map[String, String]])(any[HeaderCarrier])
      verify(mockAuditService).audit(refEq(AppTermsAndConditionsUrlChanged), any[Map[String, String]])(any[HeaderCarrier])
      verify(mockAuditService).audit(refEq(AppRedirectUrisChanged), any[Map[String, String]])(any[HeaderCarrier])
      verify(mockAuditService).audit(refEq(AppPrivacyPolicyUrlChanged), any[Map[String, String]])(any[HeaderCarrier])
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

    val applicationId = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId)
    val request = collaboratorRequest()
    val expected = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

    "throw notFoundException if no application exists in the repository for the given application id" in new Setup {
      mockApplicationRepositoryFetchToReturn(applicationId, None)

      intercept[NotFoundException](await(underTest.addCollaborator(applicationId, request)))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
    }

    "update collaborators when application exists in the repository for the given application id" in new Setup {

      mockApplicationRepositoryFetchToReturn(applicationId, Some(applicationData))
      mockApplicationRepositorySaveToReturn(successful(expected))

      private val addRequest = collaboratorRequest(isRegistered = true)
      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, addRequest))

      verify(mockApplicationRepository).save(expected)
      verify(mockAuditService).audit(CollaboratorAdded,
        AuditHelper.applicationId(applicationId) ++ CollaboratorAdded.details(addRequest.collaborator))
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "send confirmation and notification emails to the developer and all relevant administrators when adding a registered collaborator" in new Setup {

      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator(admin2, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      mockApplicationRepositoryFetchToReturn(applicationId, successful(Some(applicationData)))
      mockApplicationRepositorySaveToReturn(successful(expected))


      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest(isRegistered = true)))

      verify(mockApplicationRepository).save(expected)

      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorConfirmation("developer", applicationData.name, Set(email))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorNotification(email, "developer", applicationData.name, adminsToEmail)
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "send confirmation and notification emails to the developer and all relevant administrators when adding an unregistered collaborator" in new Setup {

      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator(admin2, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      mockApplicationRepositoryFetchToReturn(applicationId, successful(Some(applicationData)))
      mockApplicationRepositorySaveToReturn(successful(expected))


      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest()))

      verify(mockApplicationRepository).save(expected)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorConfirmation("developer", applicationData.name, Set(email))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorNotification(email, "developer", applicationData.name, adminsToEmail)
      result shouldBe AddCollaboratorResponse(registeredUser = false)
    }

    "send email confirmation to the developer and no notifications when there are no admins to email" in new Setup {

      val admin = "theonlyadmin@example.com"
      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      mockApplicationRepositoryFetchToReturn(applicationId, successful(Some(applicationData)))
      mockApplicationRepositorySaveToReturn(successful(expected))


      val result: AddCollaboratorResponse =
        await(underTest.addCollaborator(applicationId, collaboratorRequest(admin = admin, isRegistered = true, adminsToEmail = Set.empty[String])))

      verify(mockApplicationRepository).save(expected)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendAddedCollaboratorConfirmation("developer", applicationData.name, Set(email))
      verifyNoMoreInteractions(mockEmailConnector)
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "handle an unexpected failure when sending confirmation email" in new Setup {
      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator("dev@example.com", DEVELOPER))
      val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      mockApplicationRepositoryFetchToReturn(applicationId, successful(Some(applicationData)))
      mockApplicationRepositorySaveToReturn(successful(expected))

      when(mockEmailConnector.sendAddedCollaboratorConfirmation(any(), any(), any())(any())).thenReturn(failed(new RuntimeException))

      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest(isRegistered = true)))

      verify(mockApplicationRepository).save(expected)
      verify(mockEmailConnector).sendAddedCollaboratorConfirmation(any(), any(), any())(any())
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "throw UserAlreadyPresent error when adding an existing collaborator with the same role" in new Setup {
      mockApplicationRepositoryFetchToReturn(applicationId, Some(applicationData))

      intercept[UserAlreadyExists](await(underTest.addCollaborator(applicationId, collaboratorRequest(email = loggedInUser, role = ADMINISTRATOR))))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
    }

    "throw UserAlreadyPresent error when adding an existing collaborator with different role" in new Setup {
      mockApplicationRepositoryFetchToReturn(applicationId, Some(applicationData))

      intercept[UserAlreadyExists](await(underTest.addCollaborator(applicationId, collaboratorRequest(email = loggedInUser, role = DEVELOPER))))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
    }

    "throw UserAlreadyPresent error when adding an existing collaborator with different case" in new Setup {
      mockApplicationRepositoryFetchToReturn(applicationId, Some(applicationData))

      intercept[UserAlreadyExists](await(underTest.addCollaborator(applicationId, collaboratorRequest(email = loggedInUser.toUpperCase, role = DEVELOPER))))

      verify(mockApplicationRepository, never()).save(any[ApplicationData])
    }
  }

  "delete collaborator" should {
    val applicationId = UUID.randomUUID()
    val admin = "admin@example.com"
    val admin2: String = "admin2@example.com"
    val collaborator = "test@example.com"
    val adminsToEmail = Set(admin2)
    val collaborators = Set(
      Collaborator(admin, ADMINISTRATOR),
      Collaborator(admin2, ADMINISTRATOR),
      Collaborator(collaborator, DEVELOPER))
    val applicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
    val updatedData = applicationData.copy(collaborators = applicationData.collaborators - Collaborator(collaborator, DEVELOPER))

    "throw not found exception when no application exists in the repository for the given application id" in new Setup {

      mockApplicationRepositoryFetchToReturn(applicationId, None)

      intercept[NotFoundException](await(underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmail)))
      verify(mockApplicationRepository, never()).save(any[ApplicationData])
      verifyZeroInteractions(mockEmailConnector)
    }

    "remove collaborator and send confirmation and notification emails" in new Setup {


      mockApplicationRepositoryFetchToReturn(applicationId, Some(applicationData))
      mockApplicationRepositorySaveToReturn(successful(updatedData))

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, collaborator, admin, adminsToEmail))

      verify(mockApplicationRepository).save(updatedData)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      verify(mockAuditService).audit(CollaboratorRemoved,
        AuditHelper.applicationId(applicationId) ++ CollaboratorRemoved.details(Collaborator(collaborator, DEVELOPER)))
      result shouldBe updatedData.collaborators
    }

    "remove collaborator with email address in different case" in new Setup {

      mockApplicationRepositoryFetchToReturn(applicationId, Some(applicationData))
      mockApplicationRepositorySaveToReturn(successful(updatedData))

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, collaborator.toUpperCase, admin, adminsToEmail))

      verify(mockApplicationRepository).save(updatedData)
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector, Mockito.timeout(mockitoTimeout)).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      result shouldBe updatedData.collaborators
    }

    "fail to delete last remaining admin user" in new Setup {
      val collaborators: Set[Collaborator] = Set(
        Collaborator(admin, ADMINISTRATOR),
        Collaborator(collaborator, DEVELOPER))
      val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      mockApplicationRepositoryFetchToReturn(applicationId, Some(applicationData))

      intercept[ApplicationNeedsAdmin](await(underTest.deleteCollaborator(applicationId, admin, admin, adminsToEmail)))
      verify(mockApplicationRepository, never()).save(any[ApplicationData])
      verifyZeroInteractions(mockEmailConnector)
    }
  }

  "fetchByClientId" should {

    "return none when no application exists in the repository for the given client id" in new Setup {

      val clientId = "some-client-id"
      when(mockApplicationRepository.fetchByClientId(clientId)).thenReturn(None)

      val result: Option[ApplicationResponse] = await(underTest.fetchByClientId(clientId))

      result shouldBe None
    }

    "return an application when it exists in the repository for the given client id" in new Setup {

      val applicationId: UUID = UUID.randomUUID()
      val applicationData: ApplicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchByClientId(applicationData.tokens.production.clientId)).thenReturn(Some(applicationData))

      val result: Option[ApplicationResponse] = await(underTest.fetchByClientId(applicationData.tokens.production.clientId))

      result.get.id shouldBe applicationId
      result.get.environment shouldBe Some(PRODUCTION)
      result.get.collaborators shouldBe applicationData.collaborators
      result.get.createdOn shouldBe applicationData.createdOn
    }

    "return an application with trusted flag when the application is in the whitelist" in new Setup {

      val applicationData: ApplicationData = anApplicationData(trustedApplicationId1)

      when(mockApplicationRepository.fetchByClientId(applicationData.tokens.production.clientId)).thenReturn(Some(applicationData))

      val result: Option[ApplicationResponse] = await(underTest.fetchByClientId(applicationData.tokens.production.clientId))

      result.get.trusted shouldBe true
    }

  }

  "fetchByServerToken" should {

    val serverToken = "b3c83934c02df8b111e7f9f8700000"

    "return none when no application exists in the repository for the given server token" in new Setup {

      when(mockApplicationRepository.fetchByServerToken(serverToken)).thenReturn(None)

      val result: Option[ApplicationResponse] = await(underTest.fetchByServerToken(serverToken))

      result shouldBe None
    }

    "return an application when it exists in the repository for the given server token" in new Setup {

      val productionToken = EnvironmentToken("aaa", "wso2Secret", serverToken, Seq(aSecret("secret1"), aSecret("secret2")))

      val applicationId: UUID = UUID.randomUUID()
      val applicationData: ApplicationData = anApplicationData(applicationId).copy(tokens = ApplicationTokens(productionToken))

      when(mockApplicationRepository.fetchByServerToken(serverToken)).thenReturn(Some(applicationData))

      val result: Option[ApplicationResponse] = await(underTest.fetchByServerToken(serverToken))

      result.get.id shouldBe applicationId
      result.get.collaborators shouldBe applicationData.collaborators
      result.get.createdOn shouldBe applicationData.createdOn
    }
  }

  "fetchAllForCollaborator" should {

    "fetch all applications for a given collaborator email address" in new Setup {
      val applicationId: UUID = UUID.randomUUID()
      val emailAddress = "user@example.com"
      val standardApplicationData: ApplicationData = anApplicationData(applicationId, access = Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(applicationId, access = Privileged())
      val ropcApplicationData: ApplicationData = anApplicationData(applicationId, access = Ropc())

      when(mockApplicationRepository.fetchAllForEmailAddress(emailAddress))
        .thenReturn(successful(Seq(standardApplicationData, privilegedApplicationData, ropcApplicationData)))

      await(underTest.fetchAllForCollaborator(emailAddress)).size shouldBe 3
    }

  }

  "fetchAllBySubscription" should {

    "return applications for a given subscription to an API context" in new Setup {

      val applicationId: UUID = UUID.randomUUID()
      val apiContext = "some-context"
      val applicationData: ApplicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchAllForContext(apiContext)).thenReturn(successful(Seq(applicationData)))
      val result: Seq[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiContext))

      result.size shouldBe 1
      result shouldBe Seq(applicationData).map(app => ApplicationResponse(data = app, trusted = false))
    }

    "return no matching applications for a given subscription to an API context" in new Setup {

      val applicationId: UUID = UUID.randomUUID()
      val apiContext = "some-context"
      val applicationData: ApplicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchAllForContext(apiContext)).thenReturn(successful(Nil))
      val result: Seq[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiContext))

      result.size shouldBe 0
    }

    "return applications for a given subscription to an API identifier" in new Setup {

      val applicationId: UUID = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "some-version")
      val applicationData: ApplicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchAllForApiIdentifier(apiIdentifier)).thenReturn(successful(Seq(applicationData)))
      val result: Seq[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiIdentifier))

      result.size shouldBe 1
      result shouldBe Seq(applicationData).map(app => ApplicationResponse(data = app, trusted = false))
    }

    "return no matching applications for a given subscription to an API identifier" in new Setup {

      val applicationId: UUID = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "some-version")
      val applicationData: ApplicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchAllForApiIdentifier(apiIdentifier)).thenReturn(successful(Nil))
      val result: Seq[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiIdentifier))

      result.size shouldBe 0
    }
  }

  "fetchAllWithNoSubscriptions" should {

    "return no matching applications if application has a subscription" in new Setup {

      val applicationId: UUID = UUID.randomUUID()
      val apiContext = "some-context"
      val applicationData: ApplicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchAllWithNoSubscriptions()).thenReturn(successful(Nil))
      val result: Seq[ApplicationResponse] = await(underTest.fetchAllWithNoSubscriptions())

      result.size shouldBe 0
    }

    "return applications when there are no matching subscriptions" in new Setup {

      val applicationId: UUID = UUID.randomUUID()
      val apiContext = "some-context"
      val applicationData: ApplicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetchAllWithNoSubscriptions()).thenReturn(successful(Seq(applicationData)))
      val result: Seq[ApplicationResponse] = await(underTest.fetchAllWithNoSubscriptions())

      result.size shouldBe 1
      result shouldBe Seq(applicationData).map(app => ApplicationResponse(data = app, trusted = false))
    }
  }

  "verifyUplift" should {
    val applicationId = UUID.randomUUID()
    val upliftRequestedBy = "email@example.com"

    "update the state of the application when application is in pendingRequesterVerification state" in new Setup {
      val expectedStateHistory = StateHistory(applicationId, State.PRODUCTION, Actor(upliftRequestedBy, COLLABORATOR), Some(PENDING_REQUESTER_VERIFICATION))
      val upliftRequest = StateHistory(applicationId, PENDING_GATEKEEPER_APPROVAL, Actor(upliftRequestedBy, COLLABORATOR), Some(TESTING))

      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      val expectedApplication: ApplicationData = application.copy(state = productionState(upliftRequestedBy))

      when(mockApplicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)).thenReturn(Some(application))
      when(mockStateHistoryRepository.fetchLatestByStateForApplication(applicationId, PENDING_GATEKEEPER_APPROVAL)).thenReturn(Some(upliftRequest))

      val result: ApplicationStateChange = await(underTest.verifyUplift(generatedVerificationCode))
      verify(mockApplicationRepository).save(expectedApplication)
      result shouldBe UpliftVerified
      verify(mockStateHistoryRepository).insert(expectedStateHistory)
    }

    "fail if the application save fails" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))
      val saveException = new RuntimeException("application failed to save")

      when(mockApplicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)).thenReturn(Some(application))
      mockApplicationRepositorySaveToReturn(failed(saveException))

      intercept[RuntimeException] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "rollback if saving the state history fails" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      when(mockApplicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)).thenReturn(Some(application))
      when(mockStateHistoryRepository.insert(any())).thenReturn(failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }

      verify(mockApplicationRepository).save(application)
    }

    "not update the state but result in success of the application when application is already in production state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, productionState(upliftRequestedBy))

      when(mockApplicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)).thenReturn(Some(application))

      val result: ApplicationStateChange = await(underTest.verifyUplift(generatedVerificationCode))
      verify(mockApplicationRepository, times(0)).save(any[ApplicationData])
      result shouldBe UpliftVerified
    }

    "fail when application is in testing state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())

      when(mockApplicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)).thenReturn(Some(application))
      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "fail when application is in pendingGatekeeperApproval state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      when(mockApplicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)).thenReturn(Some(application))
      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "fail when application is not found by verification code" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      when(mockApplicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)).thenReturn(None)
      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }
  }

  "validate application name" should {


    "allow valid name" in new Setup {

      when(mockNameValidationConfig.nameBlackList).thenReturn(Seq("HMRC"))

      val result = await(underTest.validateApplicationName("my application name"))

      result shouldBe Valid
    }

    "block a name with HMRC in" in new Setup {
      when(mockNameValidationConfig.nameBlackList).thenReturn(Seq("HMRC"))

      val result = await(underTest.validateApplicationName("Invalid name HMRC"))

      result shouldBe Invalid.invalidName
    }

    "block a name with multiple blacklisted names in" in new Setup {
      when(mockNameValidationConfig.nameBlackList).thenReturn(Seq("InvalidName1", "InvalidName2", "InvalidName3"))

      val result = await(underTest.validateApplicationName("ValidName InvalidName1 InvalidName2"))

      result shouldBe Invalid.invalidName
    }

    "block an invalid ignoring case" in new Setup {
      when(mockNameValidationConfig.nameBlackList).thenReturn(Seq("InvalidName"))

      val result = await(underTest.validateApplicationName("invalidname"))

      result shouldBe Invalid.invalidName
    }
  }

  "requestUplift" should {
    val applicationId = UUID.randomUUID()
    val requestedName = "application name"
    val upliftRequestedBy = "email@example.com"

    "update the state of the application" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val expectedApplication: ApplicationData = application.copy(state = pendingGatekeeperApprovalState(upliftRequestedBy),
        name = requestedName, normalisedName = requestedName.toLowerCase)
      val expectedStateHistory = StateHistory(applicationId = expectedApplication.id, state = PENDING_GATEKEEPER_APPROVAL,
        actor = Actor(upliftRequestedBy, COLLABORATOR), previousState = Some(TESTING))

      mockApplicationRepositoryFetchToReturn(applicationId, Some(application))
      when(mockApplicationRepository.fetchApplicationByName(requestedName)).thenReturn(None)

      val result: ApplicationStateChange = await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))

      verify(mockApplicationRepository).save(expectedApplication)
      result shouldBe UpliftRequested
      verify(mockStateHistoryRepository).insert(expectedStateHistory)
    }

    "rollback the application when storing the state history fails" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())

      mockApplicationRepositoryFetchToReturn(applicationId, Some(application))
      when(mockApplicationRepository.fetchApplicationByName(requestedName)).thenReturn(None)
      when(mockStateHistoryRepository.insert(any())).thenReturn(failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }

      verify(mockApplicationRepository).save(application)
    }

    "send an Audit event when an application uplift is successfully requested with no name change" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val expectedApplication: ApplicationData = application.copy(state = pendingGatekeeperApprovalState(upliftRequestedBy),
        name = requestedName, normalisedName = requestedName.toLowerCase)


      mockApplicationRepositoryFetchToReturn(applicationId, Some(application))
      when(mockApplicationRepository.fetchApplicationByName(application.name)).thenReturn(None)

      val result: ApplicationStateChange = await(underTest.requestUplift(applicationId, application.name, upliftRequestedBy))
      verify(mockAuditService).audit(ApplicationUpliftRequested, Map("applicationId" -> application.id.toString))
    }

    "send an Audit event when an application uplift is successfully requested with a name change" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val expectedApplication: ApplicationData = application.copy(state = pendingGatekeeperApprovalState(upliftRequestedBy),
        name = requestedName, normalisedName = requestedName.toLowerCase)


      mockApplicationRepositoryFetchToReturn(applicationId, Some(application))
      when(mockApplicationRepository.fetchApplicationByName(requestedName)).thenReturn(None)

      val result: ApplicationStateChange = await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))

      val expectedAuditDetails: Map[String, String] = Map("applicationId" -> application.id.toString, "newApplicationName" -> requestedName)
      verify(mockAuditService).audit(ApplicationUpliftRequested, expectedAuditDetails)
    }

    "fail with InvalidStateTransition without invoking fetchNonTestingApplicationByName when the application is not in testing" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState("test@example.com"))

      mockApplicationRepositoryFetchToReturn(applicationId, Some(application))

      intercept[InvalidStateTransition] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
      verify(mockApplicationRepository, never).fetchApplicationByName(requestedName)
    }

    "fail with ApplicationAlreadyExists when another uplifted application already exist with the same name" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val anotherApplication: ApplicationData = anApplicationData(UUID.randomUUID(), productionState("admin@example.com"))

      mockApplicationRepositoryFetchToReturn(applicationId, Some(application))
      when(mockApplicationRepository.fetchApplicationByName(requestedName)).thenReturn(Some(anotherApplication))

      intercept[ApplicationAlreadyExists] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
      val expectedAuditDetails: Map[String, String] = Map("applicationId" -> application.id.toString, "applicationName" -> requestedName)
      verify(mockAuditService).audit(ApplicationUpliftRequestDeniedDueToNonUniqueName, expectedAuditDetails)
    }

    "propagate the exception when the repository fail" in new Setup {

      mockApplicationRepositoryFetchToReturn(applicationId, failed(new RuntimeException("Expected test failure")))

      intercept[RuntimeException] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
    }
  }

  "update rate limit tier" should {

    val uuid: UUID = UUID.randomUUID()
    val originalApplicationData: ApplicationData = anApplicationData(uuid)
    val updatedApplicationData: ApplicationData = originalApplicationData copy (rateLimitTier = Some(SILVER))
    val apiIdentifier: APIIdentifier = APIIdentifier("myContext", "myVersion")
    val anotherApiIdentifier: APIIdentifier = APIIdentifier("myContext-2", "myVersion-2")

    "update the application in wso2 and mongo, and re-subscribe to the apis" in new Setup {

      mockApplicationRepositoryFetchToReturn(uuid, Some(originalApplicationData))
      mockApplicationRepositorySaveToReturn(updatedApplicationData)
      when(mockApiGatewayStore.getSubscriptions(
        originalApplicationData.wso2Username, originalApplicationData.wso2Password, originalApplicationData.wso2ApplicationName))
        .thenReturn(successful(Seq(apiIdentifier, anotherApiIdentifier)))
      when(mockApiGatewayStore.checkApplicationRateLimitTier(originalApplicationData.wso2Username, originalApplicationData.wso2Password,
        originalApplicationData.wso2ApplicationName, SILVER)).thenReturn(successful(HasSucceeded))

      await(underTest updateRateLimitTier(uuid, SILVER))

      verify(mockApiGatewayStore) updateApplication(originalApplicationData, SILVER)

      verify(mockApiGatewayStore) resubscribeApi(Seq(apiIdentifier, anotherApiIdentifier), originalApplicationData.wso2Username,
        originalApplicationData.wso2Password, originalApplicationData.wso2ApplicationName, apiIdentifier, SILVER)
      verify(mockApiGatewayStore) resubscribeApi(Seq(apiIdentifier, anotherApiIdentifier), originalApplicationData.wso2Username,
        originalApplicationData.wso2Password, originalApplicationData.wso2ApplicationName, anotherApiIdentifier, SILVER)

      verify(mockApplicationRepository) save updatedApplicationData
    }

    "fail fast when retrieving application data fails" in new Setup {

      mockApplicationRepositoryFetchToReturn(uuid, failed(new RuntimeException))
      mockSubscriptionRepositoryGetSubscriptionsToReturn(uuid, Seq(apiIdentifier))

      intercept[RuntimeException] {
        await(underTest updateRateLimitTier(uuid, SILVER))
      }

      verify(mockApiGatewayStore, never).updateApplication(any[ApplicationData], any[RateLimitTier])(any[HeaderCarrier])
      verify(mockApiGatewayStore, never).resubscribeApi(any[Seq[APIIdentifier]], anyString, anyString, anyString,
        any[APIIdentifier], any[RateLimitTier])(any[HeaderCarrier])
      verify(mockApplicationRepository, never) save updatedApplicationData
    }

    "fail fast when wso2 application update fails" in new Setup {

      mockApplicationRepositoryFetchToReturn(uuid, Some(originalApplicationData))
      mockWso2ApiStoreUpdateApplicationToReturn(failed(new RuntimeException))
      mockSubscriptionRepositoryGetSubscriptionsToReturn(uuid, Seq(apiIdentifier))

      intercept[RuntimeException] {
        await(underTest updateRateLimitTier(uuid, SILVER))
      }

      verify(mockApiGatewayStore).updateApplication(any[ApplicationData], any[RateLimitTier])(any[HeaderCarrier])
      verify(mockApiGatewayStore, never).resubscribeApi(any[Seq[APIIdentifier]], anyString, anyString, anyString,
        any[APIIdentifier], any[RateLimitTier])(any[HeaderCarrier])
      verify(mockApplicationRepository, never) save updatedApplicationData
    }

    "fail when wso2 resubscribe fails, updating the application in wso2, but leaving the Mongo application in a wrong state" in new Setup {

      mockApplicationRepositoryFetchToReturn(uuid, Some(originalApplicationData))
      mockSubscriptionRepositoryGetSubscriptionsToReturn(uuid, Seq(apiIdentifier))
      mockWso2SubscribeToReturn(failed(new RuntimeException))


      intercept[RuntimeException] {
        await(underTest updateRateLimitTier(uuid, SILVER))
      }

      verify(mockApiGatewayStore).updateApplication(any[ApplicationData], any[RateLimitTier])(any[HeaderCarrier])
      verify(mockApplicationRepository, never) save updatedApplicationData
    }

    "fail when one single api fails to resubscribe in wso2, updating the application in wso2, but leaving the Mongo application" +
      " and some APIs (in wso2) in a wrong state" in new Setup {

      mockApplicationRepositoryFetchToReturn(uuid, Some(originalApplicationData))
      mockApplicationRepositorySaveToReturn(updatedApplicationData)
      when(mockApiGatewayStore.getSubscriptions(
        originalApplicationData.wso2Username, originalApplicationData.wso2Password, originalApplicationData.wso2ApplicationName))
        .thenReturn(successful(Seq(apiIdentifier, anotherApiIdentifier)))

      when(mockApiGatewayStore.checkApplicationRateLimitTier(originalApplicationData.wso2Username, originalApplicationData.wso2Password,
        originalApplicationData.wso2ApplicationName, SILVER)).thenReturn(successful(HasSucceeded))

      when(mockApiGatewayStore
        .resubscribeApi(
          Seq(apiIdentifier, anotherApiIdentifier), originalApplicationData.wso2Username, originalApplicationData.wso2Password,
          originalApplicationData.wso2ApplicationName, apiIdentifier, SILVER))
        .thenReturn(successful(HasSucceeded))
      when(mockApiGatewayStore
        .resubscribeApi(
          Seq(apiIdentifier, anotherApiIdentifier), originalApplicationData.wso2Username, originalApplicationData.wso2Password,
          originalApplicationData.wso2ApplicationName, anotherApiIdentifier, SILVER))
        .thenReturn(failed(new RuntimeException))

      intercept[RuntimeException] {
        await(underTest updateRateLimitTier(uuid, SILVER))
      }

      verify(mockApiGatewayStore).updateApplication(originalApplicationData, SILVER)

      verify(mockApiGatewayStore).resubscribeApi(Seq(apiIdentifier, anotherApiIdentifier), originalApplicationData.wso2Username,
        originalApplicationData.wso2Password, originalApplicationData.wso2ApplicationName, apiIdentifier, SILVER)
      verify(mockApiGatewayStore).resubscribeApi(Seq(apiIdentifier, anotherApiIdentifier), originalApplicationData.wso2Username,
        originalApplicationData.wso2Password, originalApplicationData.wso2ApplicationName, anotherApiIdentifier, SILVER)

      verify(mockApplicationRepository, never) save updatedApplicationData
    }
  }

  "Search" should {
    "return results based on provided ApplicationSearch" in new Setup {
      val standardApplicationData: ApplicationData = anApplicationData(UUID.randomUUID(), access = Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(UUID.randomUUID(), access = Privileged())
      val ropcApplicationData: ApplicationData = anApplicationData(UUID.randomUUID(), access = Ropc())

      val mockApplicationSearch: ApplicationSearch = mock[ApplicationSearch]

      when(mockApplicationRepository.searchApplications(mockApplicationSearch))
        .thenReturn(successful(PaginatedApplicationData(
          Seq(standardApplicationData, privilegedApplicationData, ropcApplicationData), Seq(PaginationTotal(3)), Seq(PaginationTotal(3)))))

      val result: PaginatedApplicationResponse = await(underTest.searchApplications(mockApplicationSearch))

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
        redirectUris = Seq("http://example.com/redirect"),
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
                                environment: Environment = Environment.PRODUCTION) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      "aaaaaaaaaa",
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      state,
      access,
      HmrcTime.now,
      Some(HmrcTime.now),
      rateLimitTier = rateLimitTier,
      environment = environment.toString)
  }
}
