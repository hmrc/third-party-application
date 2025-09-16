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

package uk.gov.hmrc.thirdpartyapplication.services

import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.mockito.captor.ArgCaptor
import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.mongo.lock.LockRepository
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborators.{Administrator, Developer}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.{
  CreateApplicationRequest,
  CreateApplicationRequestV1,
  CreateApplicationRequestV2,
  CreationAccess,
  StandardAccessDataToCopy
}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UpdateLoginRedirectUris
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.mocks.ApiSubscriptionFieldsConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.DeleteApplicationRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateExamples, Deleted}
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationQueries
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.testutils.NoOpMetricsTimer
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class ApplicationServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with StoredApplicationFixtures
    with CollaboratorTestData
    with UpliftRequestSamples
    with ActorTestData
    with ApplicationWithCollaboratorsFixtures
    with FixedClock {

  val requestedByName                  = "john smith"
  val requestedByEmail                 = "john.smith@example.com".toLaxEmail
  var actorSystem: Option[ActorSystem] = None

  override protected def beforeAll(): Unit = {
    actorSystem = Some(ActorSystem("ApplicationServiceSpec"))
  }

  override protected def afterAll(): Unit = {
    actorSystem.map(as =>
      await(as.terminate())
    )
  }

  trait Setup
      extends AuditServiceMockModule
      with ApiGatewayStoreMockModule
      with ApiSubscriptionFieldsConnectorMockModule
      with QueryServiceMockModule
      with ApplicationRepositoryMockModule
      with TokenServiceMockModule
      with SubmissionsServiceMockModule
      with UpliftNamingServiceMockModule
      with StateHistoryRepositoryMockModule
      with SubscriptionRepositoryMockModule
      with NotificationRepositoryMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule
      with ApplicationCommandDispatcherMockModule {

    val applicationId: ApplicationId       = ApplicationIdData.one
    val applicationData: StoredApplication = storedApp

    lazy val locked                               = false
    protected val mockitoTimeout                  = 1000
    val mockEmailConnector: EmailConnector        = mock[EmailConnector]
    val mockTotpConnector: TotpConnector          = mock[TotpConnector]
    val mockLockKeeper                            = new MockLockService(locked)
    val response                                  = mock[HttpResponse]
    val mockThirdPartyDelegatedAuthorityConnector = mock[ThirdPartyDelegatedAuthorityConnector]
    val mockGatekeeperService                     = mock[GatekeeperService]
    val mockApiPlatformEventService               = mock[ApiPlatformEventService]

    val metrics = mock[Metrics]

    val hcForLoggedInCollaborator = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> adminTwo.emailAddress.text,
      LOGGED_IN_USER_NAME_HEADER  -> "John Smith"
    )

    val hcForLoggedInGatekeeperUser = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> gatekeeperUser,
      LOGGED_IN_USER_NAME_HEADER  -> "Bob Bentley"
    )

    implicit val hc: HeaderCarrier = hcForLoggedInCollaborator

    val mockCredentialGenerator: CredentialGenerator = mock[CredentialGenerator]
    val mockNameValidationConfig                     = mock[ApplicationNamingService.Config]

    when(mockNameValidationConfig.validateForDuplicateAppNames)
      .thenReturn(true)

    val underTest = new ApplicationService(
      metrics,
      QueryServiceMock.aMock,
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      SubscriptionRepoMock.aMock,
      NotificationRepositoryMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      TermsOfUseInvitationRepositoryMock.aMock,
      AuditServiceMock.aMock,
      mockApiPlatformEventService,
      mockEmailConnector,
      mockTotpConnector,
      actorSystem.get,
      mockLockKeeper,
      ApiGatewayStoreMock.aMock,
      mockCredentialGenerator,
      ApiSubscriptionFieldsConnectorMock.aMock,
      mockThirdPartyDelegatedAuthorityConnector,
      TokenServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      UpliftNamingServiceMock.aMock,
      ApplicationCommandDispatcherMock.aMock,
      clock
    ) with NoOpMetricsTimer

    when(mockCredentialGenerator.generate()).thenReturn("a" * 10)
    StateHistoryRepoMock.Insert.thenAnswer()
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*[LaxEmailAddress], *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedNotification(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationDeletedNotification(*[ApplicationName], *[ApplicationId], *[LaxEmailAddress], *)(*)).thenReturn(successful(HasSucceeded))

    UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenSucceeds()
    SubmissionsServiceMock.DeleteAll.thenReturn()
  }

  trait LockedSetup extends Setup {

    override lazy val locked = true
  }

  trait SetupForAuditTests extends Setup {

    def setupAuditTests(access: Access): (StoredApplication, UpdateLoginRedirectUris) = {
      val existingApplication = storedApp

      val newRedirectUris                       = List(LoginRedirectUri.unsafeApply("https://new-url.example.com"))
      val updatedApplication: StoredApplication = existingApplication.copy(
        name = ApplicationName("new name"),
        normalisedName = "new name"
      )
        .withAccess(
          access match {
            case _: Access.Standard => Access.Standard(
                newRedirectUris,
                List.empty,
                Some("https://new-url.example.com/terms-and-conditions"),
                Some("https://new-url.example.com/privacy-policy")
              )
            case x                  => x
          }
        )

      val updateLoginRedirectUris = UpdateLoginRedirectUris(
        actor = gatekeeperActor,
        newRedirectUris = newRedirectUris,
        timestamp = instant
      )

      ApplicationRepoMock.Fetch.thenReturn(existingApplication)
      ApplicationRepoMock.Save.thenReturn(updatedApplication)

      (updatedApplication, updateLoginRedirectUris)
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

      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequestWithCollaboratorWithUserId(access = CreationAccess.Standard, environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: StoredApplication = storedApp.withId(createdApp.application.id).withState(appStateTesting).withCollaborators(adminTwo).copy(description = None)

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.TESTING, Actors.AppCollaborator(adminTwo.emailAddress), changedAt = instant))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name.value,
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

      val expectedApplicationData: StoredApplication = storedApp.copy(
        id = createdApp.application.id,
        state = appStateTesting,
        collaborators = Set(loggedInUserAdminCollaborator),
        access = Access.Standard().copy(sellResellOrDistribute = Some(sellResellOrDistribute)),
        description = None
      )

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.TESTING, Actors.AppCollaborator(adminTwo.emailAddress), changedAt = instant))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name.value,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }

    "create a new standard application in Mongo but not the API gateway for the PRINCIPAL (PRODUCTION) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApplicationRepoMock.Save.thenAnswer(successful)

      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = CreationAccess.Standard, environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: StoredApplication =
        storedApp.copy(
          id = createdApp.application.id,
          state = appStateTesting,
          collaborators = Set(loggedInUserAdminCollaborator),
          description = None
        )

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.TESTING, Actors.AppCollaborator(adminTwo.emailAddress), changedAt = instant))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name.value,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }

    "create a new standard application in Mongo and the API gateway for the SUBORDINATE (SANDBOX) environment" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = CreationAccess.Standard, environment = Environment.SANDBOX)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: StoredApplication =
        storedApp.copy(
          id = createdApp.application.id,
          state = ApplicationState(State.PRODUCTION, updatedOn = instant),
          collaborators = Set(loggedInUserAdminCollaborator),
          environment = Environment.SANDBOX
        )

      createdApp.totp shouldBe None

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(
        createdApp.application.id,
        State.PRODUCTION,
        Actors.AppCollaborator(adminTwo.emailAddress),
        changedAt = instant
      ))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name.value,
          "newApplicationDescription" -> applicationRequest.description.get
        ),
        hc
      )
    }

    "create a new Access.Privileged application in Mongo and the API gateway with a Production state" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = CreationAccess.Privileged)

      QueryServiceMock.FetchApplications.thenReturnsNothing()

      val prodTOTP                       = Totp("prodTotp", "prodTotpId")
      val totpQueue: mutable.Queue[Totp] = mutable.Queue(prodTOTP)
      when(mockTotpConnector.generateTotp()).thenAnswer(successful(totpQueue.dequeue()))

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: StoredApplication = storedApp.copy(
        id = createdApp.application.id,
        state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(adminTwo.emailAddress.text), updatedOn = instant),
        collaborators = Set(loggedInUserAdminCollaborator),
        access = Access.Privileged(totpIds = Some(TotpId("prodTotpId"))),
        description = None
      )

      createdApp.totp shouldBe Some(CreateApplicationResponse.TotpSecret(prodTOTP.secret))

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.PRODUCTION, Actors.Unknown, changedAt = instant))
      AuditServiceMock.Audit.verifyCalledWith(
        AppCreated,
        Map(
          "applicationId"             -> createdApp.application.id.value.toString,
          "newApplicationName"        -> applicationRequest.name.value,
          "newApplicationDescription" -> ""
        ),
        hc
      )
    }

    "fail with ApplicationAlreadyExists for privileged application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(CreationAccess.Privileged)

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

  "recordApplicationUsage" should {
    "update the Application and return an application with subscriptions and accessToken (serverToken)" in new Setup {
      val subscriptions = Set("myContext".asIdentifier("myVersion"))
      val clientId      = applicationData.tokens.production.clientId
      val expectedToken = applicationData.tokens.production.accessToken
      ApplicationRepoMock.FindAndRecordApplicationUsage.thenReturnWhen(clientId)(applicationData)
      SubscriptionRepoMock.Fetch.thenReturnWhen(applicationId)(subscriptions.toSeq: _*)

      val result = await(underTest.findAndRecordApplicationUsage(clientId))

      result.value._1.id shouldBe applicationId
      result.value._1.subscriptions shouldBe subscriptions
      result.value._2 shouldBe expectedToken
    }
  }

  "confirmSetupComplete" should {
    "update pre-production application state and store state history" in new Setup {
      val oldApplication     = storedApp.withState(ApplicationStateExamples.preProduction("previous@example.com", "Previous"))
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
      stateHistory.actor shouldBe Actors.AppCollaborator(requestedByEmail)
    }

    "not update application in wrong state" in new Setup {
      val oldApplication = storedApp.withState(ApplicationStateExamples.pendingGatekeeperApproval("previous@example.com", "Previous"))
      ApplicationRepoMock.Fetch.thenReturn(oldApplication)
      ApplicationRepoMock.Save.thenAnswer()

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.confirmSetupComplete(applicationId, requestedByEmail)))
      ex.getMessage shouldBe "Transition to 'PRODUCTION' state requires the application to be in 'PRE_PRODUCTION' state, but it was in 'PENDING_GATEKEEPER_APPROVAL'"
    }
  }

  "findAndRecordServerTokenUsage" should {
    "update the Application and return an application with subscriptions and server token (accessToken)" in new Setup {
      val subscriptions = Set("myContext".asIdentifier("myVersion"))
      val aServerToken  = applicationData.tokens.production.accessToken
      ApplicationRepoMock.FindAndRecordServerTokenUsage.thenReturnWhen(aServerToken)(applicationData)
      SubscriptionRepoMock.Fetch.thenReturnWhen(applicationId)(subscriptions.toSeq: _*)

      val result = await(underTest.findAndRecordServerTokenUsage(aServerToken))

      result.value._1.id shouldBe applicationId
      result.value._1.subscriptions shouldBe subscriptions
      result.value._2 shouldBe serverToken
      ApplicationRepoMock.FindAndRecordServerTokenUsage.verifyCalledWith(aServerToken)
    }
  }

  "update approval" should {
    val approvalInformation = CheckInformation(Some(ContactDetails(FullName("Tester"), LaxEmailAddress("test@example.com"), "12345677890")))

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
      val data: StoredApplication = storedApp.copy(rateLimitTier = Some(RateLimitTier.SILVER))

      ApplicationRepoMock.Fetch.thenReturn(data)

      val result = await(underTest.fetch(applicationId).value)

      result shouldBe Some(ApplicationWithCollaborators(
        CoreApplication(
          id = applicationId,
          token = productionToken.asApplicationToken,
          gatewayId = data.wso2ApplicationName,
          name = data.name,
          deployedTo = data.environment,
          description = data.description,
          createdOn = data.createdOn,
          lastAccess = data.lastAccess,
          grantLength = GrantLength.EIGHTEEN_MONTHS,
          access = data.access,
          state = data.state,
          rateLimitTier = RateLimitTier.SILVER,
          checkInformation = None,
          blocked = false,
          ipAllowlist = IpAllowlist(),
          lastActionActor = ActorType.UNKNOWN,
          deleteRestriction = DeleteRestriction.NoRestriction,
          organisationId = None
        ),
        collaborators = data.collaborators
      ))
    }
  }

  "fetchAllForCollaborators" should {
    "fetch all applications for a given collaborator user id" in new Setup {
      QueryServiceMock.FetchApplications.thenReturnsFor(
        ApplicationQueries.applicationsByUserId(userIdOne, includeDeleted = false),
        standardApp,
        privilegedApp,
        ropcApp
      )

      val result = await(underTest.fetchAllForCollaborators(List(userIdOne)))
      result should contain theSameElementsAs List(standardApp, privilegedApp, ropcApp)
    }

    "fetch all applications for two given collaborator user ids" in new Setup {
      QueryServiceMock.FetchApplications.thenReturnsFor(ApplicationQueries.applicationsByUserId(userIdOne, includeDeleted = false), standardApp)
      QueryServiceMock.FetchApplications.thenReturnsFor(ApplicationQueries.applicationsByUserId(userIdTwo, includeDeleted = false), standardApp2)

      val result = await(underTest.fetchAllForCollaborators(List(userIdOne, userIdTwo)))
      result should contain theSameElementsAs List(standardApp, standardApp2)
    }

    "deduplicate applications if more than one user belongs to the same application" in new Setup {
      QueryServiceMock.FetchApplications.thenReturnsFor(ApplicationQueries.applicationsByUserId(userIdOne, includeDeleted = false), standardApp)
      QueryServiceMock.FetchApplications.thenReturnsFor(ApplicationQueries.applicationsByUserId(userIdTwo, includeDeleted = false), standardApp, standardApp2)

      val result = await(underTest.fetchAllForCollaborators(List(userIdOne, userIdTwo)))
      result should contain theSameElementsAs List(standardApp, standardApp2)
    }
  }

  "deleting an application" should {
    trait DeleteApplicationSetup extends Setup {
      val deleteRequestedBy = "email@example.com".toLaxEmail
      val gatekeeperUserId  = "big.boss.gatekeeper"
      val request           = DeleteApplicationRequest(gatekeeperUserId, deleteRequestedBy)
      val api1              = "hello".asIdentifier
      val api2              = "goodbye".asIdentifier

      type T = StoredApplication => Future[AuditResult]
      val mockAuditResult  = mock[Future[AuditResult]]
      val auditFunction: T = mock[T]

      when(auditFunction.apply(*)).thenReturn(mockAuditResult)

      SubscriptionRepoMock.Fetch.thenReturn(api1, api2)
      SubscriptionRepoMock.Remove.thenReturnHasSucceeded()

      StateHistoryRepoMock.Delete.thenReturnHasSucceeded()
      NotificationRepositoryMock.DeleteAllByApplicationId.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.DeleteAllByApplicationId.succeeds()
      TermsOfUseInvitationRepositoryMock.Delete.thenReturn()

      when(mockThirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(*[ClientId])(*)).thenReturn(successful(HasSucceeded))

      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
    }

    "return a state change to indicate that the application has been deleted" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      val result = await(underTest.deleteApplication(applicationId, Some(request), auditFunction))
      result shouldBe Deleted
    }

    "call to ApiGatewayStore to delete the application" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApiGatewayStoreMock.DeleteApplication.verifyCalledWith(applicationData)
    }

    "call to the API Subscription Fields service to delete subscription field data" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.verifyCalledWith(applicationData.tokens.production.clientId)
    }

    "delete the application subscriptions from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      SubscriptionRepoMock.Remove.verifyCalledWith(applicationId, api1)
      SubscriptionRepoMock.Remove.verifyCalledWith(applicationId, api2)
    }

    "delete the application from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      ApplicationRepoMock.HardDelete.verifyCalledWith(applicationId)
    }

    "delete the application state history from the repository" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      StateHistoryRepoMock.verify.deleteByApplicationId(applicationId)
    }

    "audit the application deletion" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      when(auditFunction.apply(any[StoredApplication])).thenReturn(Future.successful(mock[AuditResult]))

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(auditFunction).apply(eqTo(applicationData))
    }

    "audit the application when the deletion has not worked" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      when(auditFunction.apply(any[StoredApplication])).thenReturn(Future.failed(new RuntimeException))

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(auditFunction).apply(eqTo(applicationData))
    }

    "send the application deleted notification email" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
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
      val standardApplicationData: StoredApplication   = storedApp.copy(id = ApplicationId.random, access = Access.Standard())
      val privilegedApplicationData: StoredApplication = storedApp.copy(id = ApplicationId.random, access = Access.Privileged())
      val ropcApplicationData: StoredApplication       = storedApp.copy(id = ApplicationId.random, access = Access.Ropc())

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
      val histories = List(aHistory(standardApplicationData.id), aHistory(privilegedApplicationData.id), aHistory(ropcApplicationData.id))
      StateHistoryRepoMock.FetchDeletedByApplicationIds.thenReturnWhen(List(standardApplicationData.id, privilegedApplicationData.id, ropcApplicationData.id))(histories: _*)

      val result: PaginatedApplications = await(underTest.searchApplications(search))

      result.total shouldBe 3
      result.matching shouldBe 3
      result.applications.size shouldBe 3
    }
  }

  "getAppsForResponsibleIndividualOrAdmin" should {
    "fetch all applications for an email" in new Setup {
      val userId       = UserId.random
      val email        = LaxEmailAddress("john.doe@example.com")
      val application1 = storedApp.copy(
        id = ApplicationId.random,
        access = standardAccessWithSubmission
      ).withCollaborators(Administrator(emailAddress = email, userId = UserIdData.one))
      val application2 = storedApp.copy(
        id = ApplicationId.random,
        access = standardAccessWithSubmission
      ).withCollaborators(Developer(emailAddress = email, userId = UserIdData.two))
      val application3 = storedApp.copy(
        id = ApplicationId.random
      ).withCollaborators(Administrator(emailAddress = email, userId = UserIdData.three))

      ApplicationRepoMock.GetAppsForResponsibleIndividualOrAdmin.thenReturnWhen(LaxEmailAddressData.one)(application1, application2, application3)

      val result = await(underTest.getAppsForResponsibleIndividualOrAdmin(LaxEmailAddressData.one))
      result should contain theSameElementsAs List(application1, application2, application3).map(app => StoredApplication.asAppWithCollaborators(app))
    }
  }

  private def aNewV1ApplicationRequestWithCollaboratorWithUserId(access: CreationAccess, environment: Environment) = {
    CreateApplicationRequestV1(
      ApplicationName("MyApp"),
      access,
      Some(CoreApplicationData.appDescription),
      environment,
      Set(adminTwo),
      None
    )
  }

  private def aNewV1ApplicationRequest(access: CreationAccess = CreationAccess.Standard, environment: Environment = Environment.PRODUCTION) = {
    CreateApplicationRequestV1(ApplicationName("MyApp"), access, Some(CoreApplicationData.appDescription), environment, Set(adminTwo), None)
  }

  private def aNewV2ApplicationRequest(environment: Environment) = {
    CreateApplicationRequestV2(
      ApplicationName("MyApp"),
      StandardAccessDataToCopy(),
      Some(CoreApplicationData.appDescription),
      environment,
      Set(adminTwo),
      makeUpliftRequest(ApiIdentifier.random),
      adminTwo.emailAddress.text,
      ApplicationId.random
    )
  }

  private def aHistory(appId: ApplicationId, state: State = State.DELETED): StateHistory = {
    StateHistory(appId, state, Actors.AppCollaborator("anEmail".toLaxEmail), Some(State.TESTING), changedAt = instant)
  }
}
