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

package uk.gov.hmrc.thirdpartyapplication.services

import akka.actor.ActorSystem
import cats.implicits._
import org.mockito.captor.ArgCaptor
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.http.{BadRequestException, ForbiddenException, HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.mongo.lock.LockRepository
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.{AddCollaboratorRequest, AddCollaboratorResponse, DeleteApplicationRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType.{COLLABORATOR, GATEKEEPER}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.{RateLimitTier, SILVER}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.GatekeeperUserActor
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.ApiSubscriptionFieldsConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule, SubscriptionRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import java.time.{LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class ApplicationServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with ApplicationTestData
    with UpliftRequestSamples
    with FixedClock {

  trait Setup extends AuditServiceMockModule
      with ApplicationUpdateServiceMockModule
      with ApiGatewayStoreMockModule
      with ApiSubscriptionFieldsConnectorMockModule
      with ApplicationRepositoryMockModule
      with TokenServiceMockModule
      with SubmissionsServiceMockModule
      with UpliftNamingServiceMockModule
      with StateHistoryRepositoryMockModule
      with SubscriptionRepositoryMockModule {

    val actorSystem: ActorSystem = ActorSystem("System")

    val applicationId: ApplicationId     = ApplicationId.random
    val applicationData: ApplicationData = anApplicationData(applicationId)

    lazy val locked                               = false
    protected val mockitoTimeout                  = 1000
    val mockEmailConnector: EmailConnector        = mock[EmailConnector]
    val mockTotpConnector: TotpConnector          = mock[TotpConnector]
    val mockLockKeeper                            = new MockLockService(locked)
    val response                                  = mock[HttpResponse]
    val mockThirdPartyDelegatedAuthorityConnector = mock[ThirdPartyDelegatedAuthorityConnector]
    val mockGatekeeperService                     = mock[GatekeeperService]
    val mockApiPlatformEventService               = mock[ApiPlatformEventService]
    val applicationResponseCreator                = new ApplicationResponseCreator()

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser,
      LOGGED_IN_USER_NAME_HEADER  -> "John Smith"
    )

    val mockCredentialGenerator: CredentialGenerator = mock[CredentialGenerator]
    val mockNameValidationConfig                     = mock[ApplicationNamingService.ApplicationNameValidationConfig]

    when(mockNameValidationConfig.validateForDuplicateAppNames)
      .thenReturn(true)

    val underTest = new ApplicationService(
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      SubscriptionRepoMock.aMock,
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
      TokenServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      UpliftNamingServiceMock.aMock,
      ApplicationUpdateServiceMock.aMock,
      clock
    )

    when(mockCredentialGenerator.generate()).thenReturn("a" * 10)
    StateHistoryRepoMock.Insert.thenAnswer()
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedNotification(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationDeletedNotification(*, *[ApplicationId], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockApiPlatformEventService.sendTeamMemberAddedEvent(*, *, *)(*)).thenReturn(successful(true))
    when(mockApiPlatformEventService.sendTeamMemberRemovedEvent(*, *, *)(*)).thenReturn(successful(true))
    when(mockApiPlatformEventService.sendTeamMemberRemovedEvent(*, *, *)(*)).thenReturn(successful(true))
    when(mockApiPlatformEventService.sendRedirectUrisUpdatedEvent(*, *, *)(*)).thenReturn(successful(true))

    UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenSucceeds()
    SubmissionsServiceMock.DeleteAll.thenReturn()
  }

  trait LockedSetup extends Setup {

    override lazy val locked = true
  }
  
  trait SetupForAuditTests extends Setup {
    def setupAuditTests(access: Access): (ApplicationData, UpdateRedirectUris) = {
      val testUserEmail = "test@example.com"
      val admin = Collaborator(testUserEmail, ADMINISTRATOR, idOf(testUserEmail))
      val tokens = ApplicationTokens(
        Token(ClientId("prodId"), "prodToken")
      )

      val existingApplication = ApplicationData(
        id = applicationId,
        name = "app name",
        normalisedName = "app name",
        collaborators = Set(admin),
        wso2ApplicationName = "wso2ApplicationName",
        tokens = tokens,
        state = testingState(),
        access = access,
        createdOn = LocalDateTime.now,
        lastAccess = Some(LocalDateTime.now)
      )
      val newRedirectUris = List("http://new-url.example.com")
      val updatedApplication: ApplicationData = existingApplication.copy(
        name = "new name",
        normalisedName = "new name",
        access = access match {
          case _: Standard => Standard (
            newRedirectUris,
            Some ("http://new-url.example.com/terms-and-conditions"),
            Some ("http://new-url.example.com/privacy-policy")
          )
          case x => x
        }
      )
      val updateRedirectUris = UpdateRedirectUris(
        actor = GatekeeperUserActor("Gatekeeper Admin"),
        oldRedirectUris = List.empty,
        newRedirectUris = newRedirectUris,
        timestamp = LocalDateTime.now(clock)
      )

      ApplicationRepoMock.Fetch.thenReturn(existingApplication)
      ApplicationRepoMock.Save.thenReturn(updatedApplication)

      (updatedApplication, updateRedirectUris)
    }
  }

  class MockLockService(locked: Boolean) extends ApplicationLockService(mock[LockRepository]) {
    var callsMadeToLockKeeper: Int = 0

    override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
      callsMadeToLockKeeper = callsMadeToLockKeeper + 1
      if (locked) {
        successful(None)
      } else {
        successful(Some(Await.result(body, 1.seconds)))
      }
    }
  }

  "Create with Collaborator userId" should {

    "create a new standard application in Mongo but not the API gateway for the PRINCIPAL (PRODUCTION) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApplicationRepoMock.Save.thenAnswer(successful)

      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequestWithCollaboratorWithUserId(access = Standard(), environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData =
        anApplicationDataWithCollaboratorWithUserId(createdApp.application.id, state = testingState(), environment = Environment.PRODUCTION).copy(description = None)
        
      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, TESTING, OldActor(loggedInUser, COLLABORATOR), changedAt = LocalDateTime.now(clock)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }
  }

  "Create" should {

    "create via uplift v2 a new standard application in Mongo but not the API gateway for the PRINCIPAL (PRODUCTION) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApplicationRepoMock.Save.thenAnswer(successful)

      val applicationRequest: CreateApplicationRequest = aNewV2ApplicationRequest(environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id,
        state = testingState(),
        environment = Environment.PRODUCTION,
        access = Standard().copy(sellResellOrDistribute = Some(sellResellOrDistribute))
      )
      .copy(description = None)

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, TESTING, OldActor(loggedInUser, COLLABORATOR), changedAt = LocalDateTime.now(clock)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }

    "create a new standard application in Mongo but not the API gateway for the PRINCIPAL (PRODUCTION) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApplicationRepoMock.Save.thenAnswer(successful)

      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Standard(), environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(createdApp.application.id, state = testingState(), environment = Environment.PRODUCTION).copy(description = None)

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, TESTING, OldActor(loggedInUser, COLLABORATOR), changedAt = LocalDateTime.now(clock)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }

    "create a new standard application in Mongo and the API gateway for the SUBORDINATE (SANDBOX) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Standard(), environment = Environment.SANDBOX)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData =
        anApplicationData(createdApp.application.id, state = ApplicationState(State.PRODUCTION, updatedOn = LocalDateTime.now(clock)), environment = Environment.SANDBOX)

      createdApp.totp shouldBe None

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.PRODUCTION, OldActor(loggedInUser, COLLABORATOR), changedAt = LocalDateTime.now(clock)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name,
          "newApplicationDescription" -> applicationRequest.description.get
        ),
        hc
      )
    }

    "create a new Privileged application in Mongo and the API gateway with a Production state" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Privileged())

      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(applicationRequest.name)

      val prodTOTP                       = Totp("prodTotp", "prodTotpId")
      val totpQueue: mutable.Queue[Totp] = mutable.Queue(prodTOTP)
      when(mockTotpConnector.generateTotp()).thenAnswer(successful(totpQueue.dequeue()))

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id,
        state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser), updatedOn = LocalDateTime.now(clock)),
        access = Privileged(totpIds = Some(TotpId("prodTotpId")))
      )
      .copy(description = None)

      createdApp.totp shouldBe Some(TotpSecret(prodTOTP.secret))

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.PRODUCTION, OldActor("", GATEKEEPER), changedAt = LocalDateTime.now(clock)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }

    "create a new ROPC application in Mongo and the API gateway with a Production state" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Ropc())

      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(applicationRequest.name)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id,
        state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser), updatedOn = LocalDateTime.now(clock)),
        access = Ropc()
      )
      .copy(description = None)

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.PRODUCTION, OldActor("", GATEKEEPER), changedAt = LocalDateTime.now(clock)))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }

    "fail with ApplicationAlreadyExists for privileged application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(Privileged())

      ApplicationRepoMock.FetchByName.thenReturnWhen(applicationRequest.name)(anApplicationData(ApplicationId.random))
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenFailsWithApplicationAlreadyExists()

      intercept[ApplicationAlreadyExists] {
        await(underTest.create(applicationRequest)(hc))
      }
    }

    "fail with ApplicationAlreadyExists for ropc application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(Ropc())

      ApplicationRepoMock.FetchByName.thenReturnWhen(applicationRequest.name)(anApplicationData(ApplicationId.random))
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenFailsWithApplicationAlreadyExists()

      intercept[ApplicationAlreadyExists] {
        await(underTest.create(applicationRequest)(hc))
      }
    }

    // See https://wso2.org/jira/browse/CAPIMGT-1
    "not create the application when there is already an application being published" in new LockedSetup {
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest()

      intercept[TimeoutException] {
        await(underTest.create(applicationRequest))
      }

      mockLockKeeper.callsMadeToLockKeeper should be > 1
      ApiGatewayStoreMock.verifyZeroInteractions()
      ApplicationRepoMock.verifyZeroInteractions()
    }

    "delete application when failed to create app in the API gateway" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(environment = Environment.SANDBOX)

      private val exception = new scala.RuntimeException("failed to generate tokens")
      ApiGatewayStoreMock.CreateApplication.thenFail(exception)
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.create(applicationRequest)))
      ex.getMessage shouldBe exception.getMessage

      ApplicationRepoMock.Save.verifyNeverCalled()
      ApiGatewayStoreMock.DeleteApplication.verifyCalled()
    }

    "delete application when failed to create state history" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest()

      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
      StateHistoryRepoMock.Insert.thenFailsWith(new RuntimeException("Expected test failure"))
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()

      intercept[RuntimeException](await(underTest.create(applicationRequest)))

      val dbApplication = ApplicationRepoMock.Save.verifyCalled()
      ApiGatewayStoreMock.DeleteApplication.verifyCalled()
      ApplicationRepoMock.HardDelete.verifyCalledWith(dbApplication.id)
    }
  }

  "addTermsOfUseAcceptance" should {
    "update the repository correctly" in new Setup {
      val termsOfUseAcceptance = TermsOfUseAcceptance(
        ResponsibleIndividual(ResponsibleIndividual.Name("bob"), ResponsibleIndividual.EmailAddress("bob@example.com")),
        LocalDateTime.now(ZoneOffset.UTC),
        Submission.Id.random,
        0
      )
      val appData              = anApplicationData(ApplicationId.random)
      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(appData)
      val result               = await(underTest.addTermsOfUseAcceptance(applicationId, termsOfUseAcceptance))
      result shouldBe appData
    }
  }

  "recordApplicationUsage" should {
    "update the Application and return an ExtendedApplicationResponse" in new Setup {
      val subscriptions: List[ApiIdentifier] = List("myContext".asIdentifier("myVersion"))
      ApplicationRepoMock.RecordApplicationUsage.thenReturnWhen(applicationId)(applicationData)
      SubscriptionRepoMock.Fetch.thenReturnWhen(applicationId)(subscriptions: _*)

      val applicationResponse: ExtendedApplicationResponse = await(underTest.recordApplicationUsage(applicationId))

      applicationResponse.id shouldBe applicationId
      applicationResponse.subscriptions shouldBe subscriptions
    }
  }

  "confirmSetupComplete" should {
    "update pre-production application state and store state history" in new Setup {
      val oldApplication     = anApplicationData(applicationId, state = ApplicationState.preProduction("previous@example.com", "Previous"))
      ApplicationRepoMock.Fetch.thenReturn(oldApplication)
      ApplicationRepoMock.Save.thenAnswer()
      StateHistoryRepoMock.Insert.thenAnswer()
      val stateHistoryCaptor = ArgCaptor[StateHistory]

      val applicationResponse = await(underTest.confirmSetupComplete(applicationId, requestedByEmail))

      ApplicationRepoMock.Fetch.verifyCalledWith(applicationId)
      verify(StateHistoryRepoMock.aMock).insert(stateHistoryCaptor.capture)

      applicationResponse.id shouldBe applicationId
      applicationResponse.state.name shouldBe State.PRODUCTION

      val stateHistory = stateHistoryCaptor.value
      stateHistory.state shouldBe State.PRODUCTION
      stateHistory.previousState shouldBe Some(State.PRE_PRODUCTION)
      stateHistory.applicationId shouldBe applicationId
      stateHistory.actor shouldBe OldActor(requestedByEmail, COLLABORATOR)
    }

    "not update application in wrong state" in new Setup {
      val oldApplication = anApplicationData(applicationId, state = ApplicationState.pendingGatekeeperApproval("previous@example.com", "Previous"))
      ApplicationRepoMock.Fetch.thenReturn(oldApplication)
      ApplicationRepoMock.Save.thenAnswer()

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.confirmSetupComplete(applicationId, requestedByEmail)))
      ex.getMessage shouldBe "Transition to 'PRODUCTION' state requires the application to be in 'PRE_PRODUCTION' state, but it was in 'PENDING_GATEKEEPER_APPROVAL'"
    }
  }

  "recordServerTokenUsage" should {
    "update the Application and return an ExtendedApplicationResponse" in new Setup {
      val subscriptions: List[ApiIdentifier] = List("myContext".asIdentifier("myVersion"))
      ApplicationRepoMock.RecordServerTokenUsage.thenReturnWhen(applicationId)(applicationData)
      SubscriptionRepoMock.Fetch.thenReturnWhen(applicationId)(subscriptions: _*)

      val applicationResponse: ExtendedApplicationResponse = await(underTest.recordServerTokenUsage(applicationId))

      applicationResponse.id shouldBe applicationId
      applicationResponse.subscriptions shouldBe subscriptions
      ApplicationRepoMock.RecordServerTokenUsage.verifyCalledWith(applicationId)
    }
  }

  "Update" should {
    val applicationUpdateRequest = anUpdateRequest()

    "update an existing application if an id is provided" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(applicationData)

      await(underTest.update(applicationId, applicationUpdateRequest))

      ApplicationRepoMock.Save.verifyCalled()
    }

    "update an existing application if an id is provided and name is changed" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(applicationData)

      await(underTest.update(applicationId, applicationUpdateRequest))

      ApplicationRepoMock.Save.verifyCalled()
    }

    "send an audit event for each type of change" in new SetupForAuditTests {
      val (updatedApplication, updateRedirectUris) = setupAuditTests(Standard())
      ApplicationUpdateServiceMock.Update.thenReturnSuccess(updatedApplication)

      await(underTest.update(applicationId, UpdateApplicationRequest(updatedApplication.name)))

      AuditServiceMock.verify.audit(eqTo(AppNameChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppTermsAndConditionsUrlChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppPrivacyPolicyUrlChanged), *)(*)
      ApplicationUpdateServiceMock.Update.verifyCalledWith(updatedApplication.id, updateRedirectUris)
    }

    "throw BadRequestException when UpdateRedirectUris command fails" in new SetupForAuditTests {
      val (updatedApplication, updateRedirectUris) = setupAuditTests(Standard())
      ApplicationUpdateServiceMock.Update.thenReturnError("Error message")

      intercept[BadRequestException]{
        await(underTest.update(applicationId, UpdateApplicationRequest(updatedApplication.name)))
      }.message shouldBe "Failed to process UpdateRedirectUris command"

      AuditServiceMock.verify.audit(eqTo(AppNameChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppTermsAndConditionsUrlChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppPrivacyPolicyUrlChanged), *)(*)
      ApplicationUpdateServiceMock.Update.verifyCalledWith(updatedApplication.id, updateRedirectUris)
    }

    "not update RedirectUris or audit TermsAndConditionsUrl or PrivacyPolicyUrl for a privileged app" in new SetupForAuditTests {
      val (updatedApplication, _) = setupAuditTests(Privileged())
      ApplicationUpdateServiceMock.Update.thenReturnSuccess(updatedApplication)

      await(underTest.update(applicationId, UpdateApplicationRequest(updatedApplication.name, access = Privileged())))

      AuditServiceMock.verify.audit(eqTo(AppNameChanged), *)(*)
      ApplicationUpdateServiceMock.Update.verifyNeverCalled
    }

    "throw a NotFoundException if application doesn't exist in repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.update(applicationId, applicationUpdateRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw a ForbiddenException when trying to change the access type of an application" in new Setup {

      val privilegedApplicationUpdateRequest: UpdateApplicationRequest = applicationUpdateRequest.copy(access = Privileged())
      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[ForbiddenException](await(underTest.update(applicationId, privilegedApplicationUpdateRequest)))

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
        grantLength = data.grantLength,
        lastAccessTokenUsage = productionToken.lastAccessTokenUsage,
        redirectUris = List.empty,
        termsAndConditionsUrl = None,
        privacyPolicyUrl = None,
        access = data.access,
        state = data.state,
        rateLimitTier = SILVER
      ))
    }
  }

  "add collaborator with userId" should {
    val admin2: String = "admin2@example.com"
    val email: String  = "test@example.com"
    val adminsToEmail  = Set(admin2)

    def collaboratorRequest(email: String = email, role: Role = DEVELOPER, isRegistered: Boolean = false, adminsToEmail: Set[String] = adminsToEmail) = {
      AddCollaboratorRequest(Collaborator(email, role, idOf(email)), isRegistered, adminsToEmail)
    }

    val request = collaboratorRequest()

    "update collaborators when application exists in the repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      val expected = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)
      ApplicationRepoMock.Save.thenReturn(expected)

      private val addRequest              = collaboratorRequest(isRegistered = true)
      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, addRequest))

      ApplicationRepoMock.Save.verifyCalledWith(expected)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorAddedAudit,
        AuditHelper.applicationId(applicationId) ++ CollaboratorAddedAudit.details(addRequest.collaborator),
        hc
      )
      result shouldBe AddCollaboratorResponse(registeredUser = true)

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData), eqTo(request.collaborator.emailAddress), eqTo(request.collaborator.role.toString()))(
        any[HeaderCarrier]
      )
    }
  }

  "add collaborator" should {
    val admin: String  = "admin@example.com"
    val admin2: String = "admin2@example.com"
    val email: String  = "test@example.com"
    val adminsToEmail  = Set(admin2)

    def collaboratorRequest(email: String = email, role: Role = DEVELOPER, isRegistered: Boolean = false, adminsToEmail: Set[String] = adminsToEmail) = {
      AddCollaboratorRequest(Collaborator(email, role, idOf(email)), isRegistered, adminsToEmail)
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

      private val addRequest              = collaboratorRequest(isRegistered = true)
      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, addRequest))

      ApplicationRepoMock.Save.verifyCalledWith(expected)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorAddedAudit,
        AuditHelper.applicationId(applicationId) ++ CollaboratorAddedAudit.details(addRequest.collaborator),
        hc
      )
      result shouldBe AddCollaboratorResponse(registeredUser = true)

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData), eqTo(request.collaborator.emailAddress), eqTo(request.collaborator.role.toString()))(
        any[HeaderCarrier]
      )
    }

    "send confirmation and notification emails to the developer and all relevant administrators when adding a registered collaborator" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val collaborators: Set[Collaborator]          = Set(
        Collaborator(admin, ADMINISTRATOR, idOf(admin)),
        Collaborator(admin2, ADMINISTRATOR, idOf(admin2)),
        Collaborator(devEmail, DEVELOPER, idOf(devEmail))
      )
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData                 = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest(isRegistered = true)))

      ApplicationRepoMock.Save.verifyCalledWith(expected)

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData), eqTo(request.collaborator.emailAddress), eqTo(request.collaborator.role.toString()))(
        any[HeaderCarrier]
      )
      verify(mockEmailConnector).sendCollaboratorAddedConfirmation(Role.DEVELOPER, applicationData.name, Set(email))
      verify(mockEmailConnector).sendCollaboratorAddedNotification(email, Role.DEVELOPER, applicationData.name, adminsToEmail)
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "send confirmation and notification emails to the developer and all relevant administrators when adding an unregistered collaborator" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val collaborators: Set[Collaborator]          = Set(
        Collaborator(admin, ADMINISTRATOR, idOf(admin)),
        Collaborator(admin2, ADMINISTRATOR, idOf(admin2)),
        Collaborator(devEmail, DEVELOPER, idOf(devEmail))
      )
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData                 = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest()))

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData), eqTo(request.collaborator.emailAddress), eqTo(request.collaborator.role.toString()))(
        any[HeaderCarrier]
      )
      ApplicationRepoMock.Save.verifyCalledWith(expected)
      verify(mockEmailConnector).sendCollaboratorAddedConfirmation(Role.DEVELOPER,  applicationData.name, Set(email))
      verify(mockEmailConnector).sendCollaboratorAddedNotification(email,  Role.DEVELOPER, applicationData.name, adminsToEmail)
      result shouldBe AddCollaboratorResponse(registeredUser = false)
    }

    "send email confirmation to the developer and no notifications when there are no admins to email" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val admin                                     = "theonlyadmin@example.com"
      val collaborators: Set[Collaborator]          = Set(
        Collaborator(admin, ADMINISTRATOR, idOf(admin)),
        Collaborator(devEmail, DEVELOPER, idOf(devEmail))
      )
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData                 = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      val result: AddCollaboratorResponse =
        await(underTest.addCollaborator(applicationId, collaboratorRequest(isRegistered = true, adminsToEmail = Set.empty[String])))

      verify(mockApiPlatformEventService).sendTeamMemberAddedEvent(eqTo(applicationData), eqTo(request.collaborator.emailAddress), eqTo(request.collaborator.role.toString))(
        any[HeaderCarrier]
      )

      ApplicationRepoMock.Save.verifyCalledWith(expected)
      verify(mockEmailConnector).sendCollaboratorAddedConfirmation(Role.DEVELOPER, expected.name,  Set(email))
      verifyNoMoreInteractions(mockEmailConnector)
      result shouldBe AddCollaboratorResponse(registeredUser = true)
    }

    "handle an unexpected failure when sending confirmation email" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val collaborators: Set[Collaborator]          = Set(
        Collaborator(admin, ADMINISTRATOR, idOf(admin)),
        Collaborator(devEmail, DEVELOPER, idOf(devEmail))
      )
      override val applicationData: ApplicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val expected: ApplicationData                 = applicationData.copy(collaborators = applicationData.collaborators + request.collaborator)

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(expected)

      when(mockEmailConnector.sendCollaboratorAddedConfirmation(*, *, *)(*)).thenReturn(failed(new RuntimeException))

      val result: AddCollaboratorResponse = await(underTest.addCollaborator(applicationId, collaboratorRequest(isRegistered = true)))

      ApplicationRepoMock.Save.verifyCalledWith(expected)
      verify(mockEmailConnector).sendCollaboratorAddedConfirmation(*, *, *)(*)
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

  "delete collaborator with userId" should {
    trait DeleteCollaboratorsSetup extends Setup {
      val admin                    = "admin@example.com"
      val admin2: String           = "admin2@example.com"
      val testEmail                = "test@example.com"
      val adminsToEmail            = Set(admin2)
      val notifyCollaborator       = true
      val collaborators            = Set(
        Collaborator(admin, ADMINISTRATOR, idOf(admin)),
        Collaborator(admin2, ADMINISTRATOR, idOf(admin2)),
        Collaborator(testEmail, DEVELOPER, idOf(testEmail))
      )
      override val applicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val updatedData              = applicationData.copy(collaborators = applicationData.collaborators - Collaborator(testEmail, DEVELOPER, idOf(testEmail)))
    }

    "remove collaborator and send confirmation and notification emails" in new DeleteCollaboratorsSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedData)

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, testEmail, adminsToEmail, notifyCollaborator))

      ApplicationRepoMock.Save.verifyCalledWith(updatedData)
      verify(mockEmailConnector).sendRemovedCollaboratorConfirmation(applicationData.name, Set(testEmail))
      verify(mockEmailConnector).sendRemovedCollaboratorNotification(testEmail, applicationData.name, adminsToEmail)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorRemovedAudit,
        AuditHelper.applicationId(applicationId) ++ CollaboratorRemovedAudit.details(Collaborator(testEmail, DEVELOPER, idOf(testEmail))),
        hc
      )
      verify(mockApiPlatformEventService).sendTeamMemberRemovedEvent(eqTo(applicationData), eqTo(testEmail), eqTo("DEVELOPER"))(any[HeaderCarrier])
      result shouldBe updatedData.collaborators
    }
  }

  "delete collaborator" should {
    trait DeleteCollaboratorsSetup extends Setup {
      val admin              = "admin@example.com"
      val admin2: String     = "admin2@example.com"
      val collaborator       = "test@example.com"
      val adminsToEmail      = Set(admin2)
      val notifyCollaborator = true

      val c1 = Collaborator(admin, ADMINISTRATOR, idOf(admin))
      val c2 = Collaborator(admin2, ADMINISTRATOR, idOf(admin2))
      val c3 = Collaborator(collaborator, DEVELOPER, idOf(collaborator))

      val collaborators            = Set(c1, c2, c3)
      override val applicationData = anApplicationData(applicationId = applicationId, collaborators = collaborators)
      val updatedData              = applicationData.copy(collaborators = Set(c1, c2))
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
      verify(mockEmailConnector).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorRemovedAudit,
        AuditHelper.applicationId(applicationId) ++ CollaboratorRemovedAudit.details(Collaborator(collaborator, DEVELOPER, idOf(collaborator))),
        hc
      )
      verify(mockApiPlatformEventService).sendTeamMemberRemovedEvent(eqTo(applicationData), eqTo(collaborator), eqTo("DEVELOPER"))(any[HeaderCarrier])
      result shouldBe updatedData.collaborators
    }

    "not send confirmation email to collaborator when notifyCollaborator is set to false" in new DeleteCollaboratorsSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedData)

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, collaborator, adminsToEmail, notifyCollaborator = false))

      ApplicationRepoMock.Save.verifyCalledWith(updatedData)
      verify(mockEmailConnector, never).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      AuditServiceMock.Audit.verifyCalledWith(
        CollaboratorRemovedAudit,
        AuditHelper.applicationId(applicationId) ++ CollaboratorRemovedAudit.details(Collaborator(collaborator, DEVELOPER, idOf(collaborator))),
        hc
      )
      verify(mockApiPlatformEventService).sendTeamMemberRemovedEvent(eqTo(applicationData), eqTo(collaborator), eqTo("DEVELOPER"))(any[HeaderCarrier])
      result shouldBe updatedData.collaborators
    }

    "remove collaborator with email address in different case" in new DeleteCollaboratorsSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.Save.thenReturn(updatedData)

      AuditServiceMock.Audit.thenReturnSuccess()

      val result: Set[Collaborator] = await(underTest.deleteCollaborator(applicationId, collaborator.toUpperCase, adminsToEmail, notifyCollaborator))

      ApplicationRepoMock.Save.verifyCalledWith(updatedData)
      verify(mockEmailConnector).sendRemovedCollaboratorConfirmation(applicationData.name, Set(collaborator))
      verify(mockEmailConnector).sendRemovedCollaboratorNotification(collaborator, applicationData.name, adminsToEmail)
      result shouldBe updatedData.collaborators

      verify(mockApiPlatformEventService).sendTeamMemberRemovedEvent(eqTo(applicationData), eqTo(collaborator), eqTo("DEVELOPER"))(any[HeaderCarrier])

    }

    "fail to delete last remaining admin user" in new DeleteCollaboratorsSetup {
      val onlyOneAdmin: Set[Collaborator]          = Set(
        Collaborator(admin, ADMINISTRATOR, idOf(admin)),
        Collaborator(collaborator, DEVELOPER, idOf(collaborator))
      )
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
      val clientId = ClientId("some-client-id")
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

    "return none when no application exists in the repository for the given server token" in new Setup {
      ApplicationRepoMock.FetchByServerToken.thenReturnNoneWhen(serverToken)

      val result: Option[ApplicationResponse] = await(underTest.fetchByServerToken(serverToken))

      result shouldBe None
    }

    "return an application when it exists in the repository for the given server token" in new Setup {

      override val applicationData: ApplicationData = anApplicationData(applicationId).copy(tokens = ApplicationTokens(productionToken))

      ApplicationRepoMock.FetchByServerToken.thenReturnWhen(serverToken)(applicationData)

      val result: Option[ApplicationResponse] = await(underTest.fetchByServerToken(serverToken))

      result.get.id shouldBe applicationId
      result.get.collaborators shouldBe applicationData.collaborators
      result.get.createdOn shouldBe applicationData.createdOn
    }
  }

  "fetchAllForCollaborator" should {
    "fetch all applications for a given collaborator user id" in new Setup {
      SubscriptionRepoMock.Fetch.thenReturnWhen(applicationId)("api1".asIdentifier, "api2".asIdentifier)
      val userId                                     = UserId.random
      val standardApplicationData: ApplicationData   = anApplicationData(applicationId, access = Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(applicationId, access = Privileged())
      val ropcApplicationData: ApplicationData       = anApplicationData(applicationId, access = Ropc())

      ApplicationRepoMock.fetchAllForUserId.thenReturnWhen(userId, false)(standardApplicationData, privilegedApplicationData, ropcApplicationData)

      val result = await(underTest.fetchAllForCollaborator(userId, false))
      result.size shouldBe 3
      result.head.subscriptions.size shouldBe 2
    }

  }

  "fetchAllBySubscription" should {
    "return applications for a given subscription to an API context" in new Setup {
      val apiContext = "some-context".asContext

      ApplicationRepoMock.FetchAllForContent.thenReturnWhen(apiContext)(applicationData)
      val result: List[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiContext))

      result.size shouldBe 1
      result shouldBe List(applicationData).map(app => ApplicationResponse(data = app))
    }

    "return no matching applications for a given subscription to an API context" in new Setup {
      val apiContext = "some-context".asContext

      ApplicationRepoMock.FetchAllForContent.thenReturnEmptyWhen(apiContext)
      val result: List[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiContext))

      result.size shouldBe 0
    }

    "return applications for a given subscription to an API identifier" in new Setup {
      val apiIdentifier = "some-context".asIdentifier("some-version")

      ApplicationRepoMock.FetchAllForApiIdentifier.thenReturnWhen(apiIdentifier)(applicationData)
      val result: List[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiIdentifier))

      result.size shouldBe 1
      result shouldBe List(applicationData).map(app => ApplicationResponse(data = app))
    }

    "return no matching applications for a given subscription to an API identifier" in new Setup {
      val apiIdentifier = "some-context".asIdentifier("some-version")

      ApplicationRepoMock.FetchAllForApiIdentifier.thenReturnEmptyWhen(apiIdentifier)

      val result: List[ApplicationResponse] = await(underTest.fetchAllBySubscription(apiIdentifier))

      result.size shouldBe 0
    }
  }

  "fetchAllWithNoSubscriptions" should {

    "return no matching applications if application has a subscription" in new Setup {
      ApplicationRepoMock.FetchAllWithNoSubscriptions.thenReturnNone()

      val result: List[ApplicationResponse] = await(underTest.fetchAllWithNoSubscriptions())

      result.size shouldBe 0
    }

    "return applications when there are no matching subscriptions" in new Setup {
      ApplicationRepoMock.FetchAllWithNoSubscriptions.thenReturn(applicationData)

      val result: List[ApplicationResponse] = await(underTest.fetchAllWithNoSubscriptions())

      result.size shouldBe 1
      result shouldBe List(applicationData).map(app => ApplicationResponse(data = app))
    }
  }

  "update rate limit tier" should {

    "update the application on AWS and in mongo" in new Setup {
      val originalApplicationData: ApplicationData = anApplicationData(applicationId)
      val updatedApplicationData: ApplicationData  = originalApplicationData copy (rateLimitTier = Some(SILVER))
      ApplicationRepoMock.Fetch.thenReturn(originalApplicationData)
      ApiGatewayStoreMock.UpdateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.UpdateApplicationRateLimit.thenReturn(applicationId, SILVER)(updatedApplicationData)

      await(underTest.updateRateLimitTier(applicationId, SILVER))

      ApiGatewayStoreMock.UpdateApplication.verifyCalledWith(originalApplicationData, SILVER)
      ApplicationRepoMock.UpdateApplicationRateLimit.verifyCalledWith(applicationId, SILVER)
    }

  }

  "update IP allowlist" should {
    "update the IP allowlist in the application in Mongo" in new Setup {
      val newIpAllowlist: IpAllowlist             = IpAllowlist(required = true, Set("192.168.100.0/22", "192.168.104.1/32"))
      val updatedApplicationData: ApplicationData = anApplicationData(applicationId, ipAllowlist = newIpAllowlist)
      ApplicationRepoMock.UpdateIpAllowlist.thenReturnWhen(applicationId, newIpAllowlist)(updatedApplicationData)

      val result: ApplicationData = await(underTest.updateIpAllowlist(applicationId, newIpAllowlist))

      result shouldBe updatedApplicationData
      ApplicationRepoMock.UpdateIpAllowlist.verifyCalledWith(applicationId, newIpAllowlist)
    }

    "fail when the IP address is out of range" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpAllowlist(ApplicationId.random, IpAllowlist(required = true, Set("392.168.100.0/22"))))
      }

      error.getMessage shouldBe "Value [392] not in range [0,255]"
    }

    "fail when the mask is out of range" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpAllowlist(ApplicationId.random, IpAllowlist(required = true, Set("192.168.100.0/55"))))
      }

      error.getMessage shouldBe "Value [55] not in range [0,32]"
    }

    "fail when the format is invalid" in new Setup {
      val error: InvalidIpAllowlistException = intercept[InvalidIpAllowlistException] {
        await(underTest.updateIpAllowlist(ApplicationId.random, IpAllowlist(required = true, Set("192.100.0/22"))))
      }

      error.getMessage shouldBe "Could not parse [192.100.0/22]"
    }
  }

  "update Grant Length" should {
    "update the Grant Length in the application in Mongo" in new Setup {
      val newGrantLengthDays                      = 1000
      val updatedApplicationData: ApplicationData = anApplicationData(applicationId, grantLength = newGrantLengthDays)
      ApplicationRepoMock.UpdateGrantLength.thenReturnWhen(applicationId, newGrantLengthDays)(updatedApplicationData)

      val result: ApplicationData = await(underTest.updateGrantLength(applicationId, newGrantLengthDays))

      result shouldBe updatedApplicationData
      ApplicationRepoMock.UpdateGrantLength.verifyCalledWith(applicationId, newGrantLengthDays)
    }
  }

  "deleting an application" should {

    trait DeleteApplicationSetup extends Setup {
      val deleteRequestedBy = "email@example.com"
      val gatekeeperUserId  = "big.boss.gatekeeper"
      val request           = DeleteApplicationRequest(gatekeeperUserId, deleteRequestedBy)
      val api1              = "hello".asIdentifier
      val api2              = "goodbye".asIdentifier

      type T = ApplicationData => Future[AuditResult]
      val mockAuditResult  = mock[Future[AuditResult]]
      val auditFunction: T = mock[T]

      when(auditFunction.apply(*)).thenReturn(mockAuditResult)

      SubscriptionRepoMock.Fetch.thenReturn(api1, api2)
      SubscriptionRepoMock.Remove.thenReturnHasSucceeded()

      StateHistoryRepoMock.Delete.thenReturnHasSucceeded()

      when(mockThirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(*[ClientId])(*)).thenReturn(successful(HasSucceeded))

      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
    }

    "return a state change to indicate that the application has been deleted" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      val result = await(underTest.deleteApplication(applicationId, Some(request), auditFunction))
      result shouldBe Deleted
    }

    "call to ApiGatewayStore to delete the application" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApiGatewayStoreMock.DeleteApplication.verifyCalledWith(applicationData)
    }

    "call to the API Subscription Fields service to delete subscription field data" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.verifyCalledWith(applicationData.tokens.production.clientId)
    }

    "delete the application subscriptions from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      SubscriptionRepoMock.Remove.verifyCalledWith(applicationId, api1)
      SubscriptionRepoMock.Remove.verifyCalledWith(applicationId, api2)
    }

    "delete the application from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApplicationRepoMock.HardDelete.verifyCalledWith(applicationId)
    }

    "delete the application state history from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      StateHistoryRepoMock.verify.deleteByApplicationId(applicationId)
    }

    "audit the application deletion" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      when(auditFunction.apply(any[ApplicationData])).thenReturn(Future.successful(mock[AuditResult]))

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(auditFunction).apply(eqTo(applicationData))
    }

    "audit the application when the deletion has not worked" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      when(auditFunction.apply(any[ApplicationData])).thenReturn(Future.failed(new RuntimeException))

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(auditFunction).apply(eqTo(applicationData))
    }

    "send the application deleted notification email" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(mockEmailConnector).sendApplicationDeletedNotification(
        applicationData.name,
        applicationData.id,
        deleteRequestedBy,
        applicationData.admins.map(_.emailAddress)
      )
    }

    "silently ignore the delete request if no application exists for the application id (to ensure idempotency)" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction)) shouldBe Deleted

      ApplicationRepoMock.Fetch.verifyCalledWith(applicationId)
      verifyNoMoreInteractions(
        ApiGatewayStoreMock.aMock,
        ApplicationRepoMock.aMock,
        StateHistoryRepoMock.aMock,
        SubscriptionRepoMock.aMock,
        AuditServiceMock.aMock,
        mockEmailConnector,
        ApiSubscriptionFieldsConnectorMock.aMock
      )
    }
  }

  "Search" should {
    "return results based on provided ApplicationSearch" in new Setup {
      val standardApplicationData: ApplicationData   = anApplicationData(ApplicationId.random, access = Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(ApplicationId.random, access = Privileged())
      val ropcApplicationData: ApplicationData       = anApplicationData(ApplicationId.random, access = Ropc())

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
          )
        )
      )

      val result: PaginatedApplicationResponse = await(underTest.searchApplications(search))

      result.total shouldBe 3
      result.matching shouldBe 3
      result.applications.size shouldBe 3
    }
  }

  private def aNewV1ApplicationRequestWithCollaboratorWithUserId(access: Access, environment: Environment) = {
    CreateApplicationRequestV1(
      "MyApp",
      access,
      Some("description"),
      environment,
      Set(Collaborator(loggedInUser, ADMINISTRATOR, idOf(loggedInUser))),
      None
    )
  }

  private def anApplicationDataWithCollaboratorWithUserId(
      applicationId: ApplicationId,
      state: ApplicationState,
      collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR, idOf(loggedInUser))),
      access: Access = Standard(),
      rateLimitTier: Option[RateLimitTier] = Some(RateLimitTier.BRONZE),
      environment: Environment
    ) = {

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
      LocalDateTime.now(clock),
      Some(LocalDateTime.now(clock)),
      rateLimitTier = rateLimitTier,
      environment = environment.toString
    )
  }

  private def aNewV1ApplicationRequest(access: Access = Standard(), environment: Environment = Environment.PRODUCTION) = {
    CreateApplicationRequestV1("MyApp", access, Some("description"), environment, Set(Collaborator(loggedInUser, ADMINISTRATOR, idOf(loggedInUser))), None)
  }

  private def aNewV2ApplicationRequest(environment: Environment) = {
    CreateApplicationRequestV2(
      "MyApp",
      StandardAccessDataToCopy(),
      Some("description"),
      environment,
      Set(Collaborator(loggedInUser, ADMINISTRATOR, idOf(loggedInUser))),
      makeUpliftRequest(ApiIdentifier.random),
      loggedInUser,
      ApplicationId.random
    )
  }

  private def anUpdateRequest() = {
    UpdateApplicationRequest(
      name = "My Application",
      access = Standard().copy(redirectUris = List("http://example.com/redirect")),
      description = Some("Description")
    )
  }
}
