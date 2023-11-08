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

import akka.actor.ActorSystem
import cats.implicits._
import com.kenshoo.play.metrics.Metrics
import org.mockito.captor.ArgCaptor
import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.http.{ForbiddenException, HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.mongo.lock.LockRepository
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress, UserId, _}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.common.domain.models.FullName
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{SubmissionId, _}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UpdateRedirectUris
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.DeleteApplicationRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.ApiSubscriptionFieldsConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.testutils.NoOpMetricsTimer
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.domain.models.TotpSecret

class ApplicationServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with ApplicationTestData
    with UpliftRequestSamples
    with FixedClock {

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

    val metrics = mock[Metrics]

    val hcForLoggedInCollaborator = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser.text,
      LOGGED_IN_USER_NAME_HEADER  -> "John Smith"
    )

    val hcForLoggedInGatekeeperUser = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> gatekeeperUser,
      LOGGED_IN_USER_NAME_HEADER  -> "Bob Bentley"
    )

    implicit val hc = hcForLoggedInCollaborator

    val mockCredentialGenerator: CredentialGenerator = mock[CredentialGenerator]
    val mockNameValidationConfig                     = mock[ApplicationNamingService.ApplicationNameValidationConfig]

    when(mockNameValidationConfig.validateForDuplicateAppNames)
      .thenReturn(true)

    val underTest = new ApplicationService(
      metrics,
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
    when(mockEmailConnector.sendRemovedCollaboratorNotification(*[LaxEmailAddress], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendRemovedCollaboratorConfirmation(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedAdminConfirmation(*, *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationApprovedNotification(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockEmailConnector.sendApplicationDeletedNotification(*, *[ApplicationId], *[LaxEmailAddress], *)(*)).thenReturn(successful(HasSucceeded))

    UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenSucceeds()
    SubmissionsServiceMock.DeleteAll.thenReturn()
  }

  trait LockedSetup extends Setup {

    override lazy val locked = true
  }

  trait SetupForAuditTests extends Setup {

    def setupAuditTests(access: Access): (ApplicationData, UpdateRedirectUris) = {
      val admin  = otherAdminCollaborator
      val tokens = ApplicationTokens(
        Token(ClientId("prodId"), "prodToken")
      )

      val existingApplication                 = ApplicationData(
        id = applicationId,
        name = "app name",
        normalisedName = "app name",
        collaborators = Set(admin),
        wso2ApplicationName = "wso2ApplicationName",
        tokens = tokens,
        state = testingState(),
        access = access,
        createdOn = now,
        lastAccess = Some(now)
      )
      val newRedirectUris                     = List("http://new-url.example.com")
      val updatedApplication: ApplicationData = existingApplication.copy(
        name = "new name",
        normalisedName = "new name",
        access = access match {
          case _: Access.Standard => Access.Standard(
              newRedirectUris,
              Some("http://new-url.example.com/terms-and-conditions"),
              Some("http://new-url.example.com/privacy-policy")
            )
          case x                  => x
        }
      )
      val updateRedirectUris                  = UpdateRedirectUris(
        actor = gatekeeperActor,
        oldRedirectUris = List.empty,
        newRedirectUris = newRedirectUris,
        timestamp = now
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

      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequestWithCollaboratorWithUserId(access = Access.Standard(), environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData =
        anApplicationDataWithCollaboratorWithUserId(createdApp.application.id, state = testingState(), environment = Environment.PRODUCTION).copy(description = None)

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.TESTING, Actors.AppCollaborator(loggedInUser), changedAt = now))
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
        collaborators = Set(loggedInUserAdminCollaborator),
        environment = Environment.PRODUCTION,
        access = Access.Standard().copy(sellResellOrDistribute = Some(sellResellOrDistribute))
      )
        .copy(description = None)

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.TESTING, Actors.AppCollaborator(loggedInUser), changedAt = now))
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

      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Access.Standard(), environment = Environment.PRODUCTION)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData =
        anApplicationData(createdApp.application.id, state = testingState(), collaborators = Set(loggedInUserAdminCollaborator), environment = Environment.PRODUCTION).copy(
          description = None
        )

      createdApp.totp shouldBe None
      ApiGatewayStoreMock.CreateApplication.verifyNeverCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.TESTING, Actors.AppCollaborator(loggedInUser), changedAt = now))
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
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Access.Standard(), environment = Environment.SANDBOX)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData =
        anApplicationData(
          createdApp.application.id,
          collaborators = Set(loggedInUserAdminCollaborator),
          state = ApplicationState(State.PRODUCTION, updatedOn = now),
          environment = Environment.SANDBOX
        )

      createdApp.totp shouldBe None

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(
        createdApp.application.id,
        State.PRODUCTION,
        Actors.AppCollaborator(loggedInUser),
        changedAt = now
      ))
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

    "create a new Access.Privileged application in Mongo and the API gateway with a Production state" in new Setup {
      TokenServiceMock.CreateEnvironmentToken.thenReturn(productionToken)
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      ApplicationRepoMock.Save.thenAnswer(successful)
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Access.Privileged())

      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(applicationRequest.name)

      val prodTOTP                       = Totp("prodTotp", "prodTotpId")
      val totpQueue: mutable.Queue[Totp] = mutable.Queue(prodTOTP)
      when(mockTotpConnector.generateTotp()).thenAnswer(successful(totpQueue.dequeue()))

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id,
        state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser.text), updatedOn = now),
        collaborators = Set(loggedInUserAdminCollaborator),
        access = Access.Privileged(totpIds = Some(TotpId("prodTotpId")))
      )
        .copy(description = None)

      createdApp.totp shouldBe Some(TotpSecret(prodTOTP.secret))

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.PRODUCTION, Actors.Unknown, changedAt = now))
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
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(access = Access.Ropc())

      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(applicationRequest.name)

      val createdApp: CreateApplicationResponse = await(underTest.create(applicationRequest)(hc))

      val expectedApplicationData: ApplicationData = anApplicationData(
        createdApp.application.id,
        state = ApplicationState(name = State.PRODUCTION, requestedByEmailAddress = Some(loggedInUser.text), updatedOn = now),
        collaborators = Set(loggedInUserAdminCollaborator),
        access = Access.Ropc()
      )
        .copy(description = None)

      ApiGatewayStoreMock.CreateApplication.verifyCalled()
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplicationData)
      StateHistoryRepoMock.Insert.verifyCalledWith(StateHistory(createdApp.application.id, State.PRODUCTION, Actors.Unknown, changedAt = now))
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
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(Access.Privileged())

      ApplicationRepoMock.FetchByName.thenReturnWhen(applicationRequest.name)(anApplicationData(ApplicationId.random))
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenFailsWithApplicationAlreadyExists()

      intercept[ApplicationAlreadyExists] {
        await(underTest.create(applicationRequest)(hc))
      }
    }

    "fail with ApplicationAlreadyExists for ropc application when the name already exists for another application not in testing mode" in new Setup {
      val applicationRequest: CreateApplicationRequest = aNewV1ApplicationRequest(Access.Ropc())

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
        ResponsibleIndividual.build("bob", "bob@example.com"),
        now,
        SubmissionId.random,
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
      val clientId                           = applicationData.tokens.production.clientId
      ApplicationRepoMock.FindAndRecordApplicationUsage.thenReturnWhen(clientId)(applicationData)
      SubscriptionRepoMock.Fetch.thenReturnWhen(applicationId)(subscriptions: _*)

      val result = await(underTest.findAndRecordApplicationUsage(clientId))

      result.value.id shouldBe applicationId
      result.value.subscriptions shouldBe subscriptions
    }
  }

  "confirmSetupComplete" should {
    "update pre-production application state and store state history" in new Setup {
      val oldApplication     = anApplicationData(applicationId, state = ApplicationStateExamples.preProduction("previous@example.com", "Previous"))
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
      val oldApplication = anApplicationData(applicationId, state = ApplicationStateExamples.pendingGatekeeperApproval("previous@example.com", "Previous"))
      ApplicationRepoMock.Fetch.thenReturn(oldApplication)
      ApplicationRepoMock.Save.thenAnswer()

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.confirmSetupComplete(applicationId, requestedByEmail)))
      ex.getMessage shouldBe "Transition to 'PRODUCTION' state requires the application to be in 'PRE_PRODUCTION' state, but it was in 'PENDING_GATEKEEPER_APPROVAL'"
    }
  }

  "findAndRecordServerTokenUsage" should {
    "update the Application and return an ExtendedApplicationResponse" in new Setup {
      val subscriptions: List[ApiIdentifier] = List("myContext".asIdentifier("myVersion"))
      val serverToken                        = applicationData.tokens.production.accessToken
      ApplicationRepoMock.FindAndRecordServerTokenUsage.thenReturnWhen(serverToken)(applicationData)
      SubscriptionRepoMock.Fetch.thenReturnWhen(applicationId)(subscriptions: _*)

      val result = await(underTest.findAndRecordServerTokenUsage(serverToken))

      result.value.id shouldBe applicationId
      result.value.subscriptions shouldBe subscriptions
      ApplicationRepoMock.FindAndRecordServerTokenUsage.verifyCalledWith(serverToken)
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
      override implicit val hc = hcForLoggedInGatekeeperUser

      val (updatedApplication, updateRedirectUris) = setupAuditTests(Access.Standard())
      ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccessOn(updateRedirectUris)(updatedApplication)

      val updateAppReq = UpdateApplicationRequest(updatedApplication.name, updatedApplication.access)

      await(underTest.update(applicationId, updateAppReq))

      AuditServiceMock.verify.audit(eqTo(AppNameChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppTermsAndConditionsUrlChanged), *)(*)
      AuditServiceMock.verify.audit(eqTo(AppPrivacyPolicyUrlChanged), *)(*)
    }

    "not audit TermsAndConditionsUrl or PrivacyPolicyUrl for a privileged app" in new SetupForAuditTests {
      val (updatedApplication, _) = setupAuditTests(Access.Privileged())

      await(underTest.update(applicationId, UpdateApplicationRequest(updatedApplication.name, access = Access.Privileged())))

      AuditServiceMock.verify.audit(eqTo(AppNameChanged), *)(*)
      ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
    }

    "throw a NotFoundException if application doesn't exist in repository for the given application id" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNone()

      intercept[NotFoundException](await(underTest.update(applicationId, applicationUpdateRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "throw a ForbiddenException when trying to change the access type of an application" in new Setup {

      val privilegedApplicationUpdateRequest: UpdateApplicationRequest = applicationUpdateRequest.copy(access = Access.Privileged())
      ApplicationRepoMock.Fetch.thenReturn(applicationData)

      intercept[ForbiddenException](await(underTest.update(applicationId, privilegedApplicationUpdateRequest)))

      ApplicationRepoMock.Save.verifyNeverCalled()
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
      val data: ApplicationData = anApplicationData(applicationId, rateLimitTier = Some(RateLimitTier.SILVER))

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
        rateLimitTier = RateLimitTier.SILVER
      ))
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
      val standardApplicationData: ApplicationData   = anApplicationData(applicationId, access = Access.Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(applicationId, access = Access.Privileged())
      val ropcApplicationData: ApplicationData       = anApplicationData(applicationId, access = Access.Ropc())

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

  "deleting an application" should {
    trait DeleteApplicationSetup extends Setup {
      val deleteRequestedBy = "email@example.com".toLaxEmail
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

      when(auditFunction.apply(any[ApplicationData])).thenReturn(Future.successful(mock[AuditResult]))

      await(underTest.deleteApplication(applicationId, Some(request), auditFunction))

      verify(auditFunction).apply(eqTo(applicationData))
    }

    "audit the application when the deletion has not worked" in new DeleteApplicationSetup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.HardDelete.thenReturnHasSucceeded()
      ApiSubscriptionFieldsConnectorMock.DeleteSubscriptions.thenReturnHasSucceeded()

      when(auditFunction.apply(any[ApplicationData])).thenReturn(Future.failed(new RuntimeException))

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
      val standardApplicationData: ApplicationData   = anApplicationData(ApplicationId.random, access = Access.Standard())
      val privilegedApplicationData: ApplicationData = anApplicationData(ApplicationId.random, access = Access.Privileged())
      val ropcApplicationData: ApplicationData       = anApplicationData(ApplicationId.random, access = Access.Ropc())

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
      Set(loggedInUser.admin()),
      None
    )
  }

  private def anApplicationDataWithCollaboratorWithUserId(
      applicationId: ApplicationId,
      state: ApplicationState,
      collaborators: Set[Collaborator] = Set(loggedInUser.admin()),
      access: Access = Access.Standard(),
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
      now,
      Some(now),
      rateLimitTier = rateLimitTier,
      environment = environment.toString
    )
  }

  private def aNewV1ApplicationRequest(access: Access = Access.Standard(), environment: Environment = Environment.PRODUCTION) = {
    CreateApplicationRequestV1("MyApp", access, Some("description"), environment, Set(loggedInUser.admin()), None)
  }

  private def aNewV2ApplicationRequest(environment: Environment) = {
    CreateApplicationRequestV2(
      "MyApp",
      StandardAccessDataToCopy(),
      Some("description"),
      environment,
      Set(loggedInUser.admin()),
      makeUpliftRequest(ApiIdentifier.random),
      loggedInUser.text,
      ApplicationId.random
    )
  }

  private def anUpdateRequest() = {
    UpdateApplicationRequest(
      name = "My Application",
      access = Access.Standard().copy(redirectUris = List("http://example.com/redirect")),
      description = Some("Description")
    )
  }
}
