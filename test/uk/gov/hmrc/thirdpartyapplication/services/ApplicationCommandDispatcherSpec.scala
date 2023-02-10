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

import java.util.UUID
import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

import cats.data._

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler.CommandFailures
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationCommandDispatcherUtils
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.TermsAndConditionsLocations
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.PrivacyPolicyLocations

class ApplicationCommandDispatcherSpec extends ApplicationCommandDispatcherUtils with CommandCollaboratorExamples with CommandApplicationExamples {

  trait Setup extends CommonSetup {
    val applicationData: ApplicationData = anApplicationData(applicationId)

    def primeCommonServiceSuccess() = {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      AuditServiceMock.ApplyEvents.succeeds()
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
    }

    def verifyNoCommonServicesCalled(): Unit = {
      verifyZeroInteractions(ApiPlatformEventServiceMock.aMock)
      verifyZeroInteractions(AuditServiceMock.aMock)
      verifyZeroInteractions(NotificationServiceMock.aMock)
    }

    def verifyServicesCalledWithEvent(expectedEvent: UpdateApplicationEvent) = {

      verify(ApiPlatformEventServiceMock.aMock)
        .applyEvents(*[NonEmptyList[UpdateApplicationEvent]])(*[HeaderCarrier])

      verify(AuditServiceMock.aMock)
        .applyEvents(eqTo(applicationData), eqTo(NonEmptyList.one(expectedEvent)))(*[HeaderCarrier])

      expectedEvent match {
        case e: UpdateApplicationEvent with TriggersNotification => verify(NotificationServiceMock.aMock)
            .sendNotifications(eqTo(applicationData), eqTo(List(e)))(*[HeaderCarrier])
        case _                                                   => succeed
      }

    }

    val allCommandHandlers = Set(
      mockAddClientSecretCommandHandler,
      mockRemoveClientSecretCommandHandler,
      mockChangeProductionApplicationNameCommandHandler,
      mockAddCollaboratorCommandHandler,
      mockRemoveCollaboratorCommandHandler,
      mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
      mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
      mockChangeResponsibleIndividualToSelfCommandHandler,
      mockChangeResponsibleIndividualToOtherCommandHandler,
      mockVerifyResponsibleIndividualCommandHandler,
      mockDeclineResponsibleIndividualCommandHandler,
      mockDeclineResponsibleIndividualDidNotVerifyCommandHandler,
      mockDeclineApplicationApprovalRequestCommandHandler,
      mockDeleteApplicationByCollaboratorCommandHandler,
      mockDeleteApplicationByGatekeeperCommandHandler,
      mockDeleteUnusedApplicationCommandHandler,
      mockDeleteProductionCredentialsApplicationCommandHandler,
      mockSubscribeToApiCommandHandler,
      mockUnsubscribeFromApiCommandHandler,
      mockUpdateRedirectUrisCommandHandler
    )

    def verifyNoneButGivenCmmandHandlerCalled[A <: CommandHandler]()(implicit ct: ClassTag[A]) = {
      allCommandHandlers.filter {
        case a: A => false
        case _    => true
      }
        .foreach(handler => verifyZeroInteractions(handler))
    }

    def testFailure(cmd: ApplicationCommand) = {

      ApplicationRepoMock.Fetch.thenFail(new RuntimeException("some error"))

      intercept[RuntimeException] {
        await(underTest.dispatch(applicationId, cmd).value)
      }

      allCommandHandlers.foreach(handler => verifyZeroInteractions(handler))

      verifyNoCommonServicesCalled
    }
  }

  val timestamp         = FixedClock.now
  val gatekeeperUser    = "gkuser1"
  val jobId             = "jobId"
  val devHubUser        = Actors.Collaborator(adminEmail)
  val scheduledJobActor = Actors.ScheduledJob(jobId)
  val reasons           = "some reason or other"

  val E = EitherTHelper.make[CommandFailures]

  "dispatch" when {
    "AddClientSecret is received" should {
      val clientSecret             = ClientSecret("name", FixedClock.now, None, UUID.randomUUID().toString, "hashedSecret")
      val cmd: AddClientSecret     = AddClientSecret(devHubUser, clientSecret, FixedClock.now)
      val evt: ClientSecretAddedV2 = ClientSecretAddedV2(UpdateApplicationEvent.Id.random, applicationId, FixedClock.now, devHubUser, clientSecret.name, clientSecret.id)

      "call AddClientSecretCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockAddClientSecretCommandHandler.process(*[ApplicationData], *[AddClientSecret])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[AddClientSecretCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }
    }

    "RemoveClientSecret is received" should {
      val cmd: RemoveClientSecret  = RemoveClientSecret(devHubUser, UUID.randomUUID().toString, FixedClock.now)
      val evt: ClientSecretRemoved = ClientSecretRemoved(UpdateApplicationEvent.Id.random, applicationId, FixedClock.now, devHubUser, cmd.clientSecretId, "someName")

      "call RemoveClientSecretCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockRemoveClientSecretCommandHandler.process(*[ApplicationData], *[RemoveClientSecret])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[RemoveClientSecretCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "AddCollaborator is received" should {
      val collaborator           = Collaborator("email", Role.DEVELOPER, UserId.random)
      val adminsToEmail          = Set("email1", "email2")
      val cmd: AddCollaborator   = AddCollaborator(devHubUser, collaborator, adminsToEmail, FixedClock.now)
      val evt: CollaboratorAdded = CollaboratorAdded(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        collaborator.userId,
        collaborator.emailAddress,
        collaborator.role,
        adminsToEmail
      )

      "call AddCollaboratorCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockAddCollaboratorCommandHandler.process(*[ApplicationData], *[AddCollaborator])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[AddCollaboratorCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "RemoveCollaborator is received" should {

      val collaborator             = Collaborator("email", Role.DEVELOPER, UserId.random)
      val adminsToEmail            = Set("email1", "email2")
      val cmd: RemoveCollaborator  = RemoveCollaborator(devHubUser, collaborator, adminsToEmail, FixedClock.now)
      val evt: CollaboratorRemoved = CollaboratorRemoved(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        collaborator.userId,
        collaborator.emailAddress,
        collaborator.role,
        notifyCollaborator = true,
        adminsToEmail
      )

      "call RemoveCollaboratorCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockRemoveCollaboratorCommandHandler.process(*[ApplicationData], *[RemoveCollaborator])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[RemoveCollaboratorCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "ChangeProductionApplicationName is received" should {

      val oldName        = "old app name"
      val newName        = "new app name"
      val gatekeeperUser = "gkuser"
      val requester      = "requester"
      val actor          = Actors.GatekeeperUser(gatekeeperUser)
      val userId         = UserId.random

      val timestamp = FixedClock.now
      val cmd       = ChangeProductionApplicationName(userId, timestamp, gatekeeperUser, newName)
      val evt       = ProductionAppNameChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        actor,
        oldName,
        newName,
        requester
      )

      "call ChangeProductionApplicationName Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockChangeProductionApplicationNameCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationName])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeProductionApplicationNameCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "ChangeProductionApplicationPrivacyPolicyLocation is received" should {

      val newUrl      = "http://example.com/new"
      val newLocation = PrivacyPolicyLocations.Url(newUrl)
      val userId      = idsByEmail(adminEmail)
      val timestamp   = FixedClock.now
      val actor       = Actors.Collaborator(adminEmail)

      val cmd = ChangeProductionApplicationPrivacyPolicyLocation(userId, timestamp, newLocation)
      val evt = ProductionAppPrivacyPolicyLocationChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        actor,
        privicyPolicyLocation,
        newLocation
      )

      "call ChangeProductionApplicationPrivacyPolicyLocation Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationPrivacyPolicyLocation])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeProductionApplicationPrivacyPolicyLocationCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "ChangeProductionApplicationTermsAndConditionsLocation is received" should {

      val newUrl      = "http://example.com/new"
      val newLocation = TermsAndConditionsLocations.Url(newUrl)
      val userId      = idsByEmail(adminEmail)
      val timestamp   = FixedClock.now
      val actor       = Actors.Collaborator(adminEmail)

      val cmd = ChangeProductionApplicationTermsAndConditionsLocation(userId, timestamp, newLocation)
      val evt = ProductionAppTermsConditionsLocationChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        actor,
        termsAndConditionsLocation,
        newLocation
      )

      "call ChangeProductionApplicationTermsAndConditionsLocation Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(
          mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationTermsAndConditionsLocation])
        ).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeProductionApplicationTermsAndConditionsLocationCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    " ChangeResponsibleIndividualToSelf is received" should {

      val cmd = ChangeResponsibleIndividualToSelf(UserId.random, timestamp, requestedByName, requestedByEmail)
      val evt = ResponsibleIndividualChangedToSelf(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        "previousRIName",
        "previousRIEmail",
        Submission.Id.random,
        1,
        requestedByName,
        requestedByEmail
      )

      "call  ChangeResponsibleIndividualToSelf Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(
          mockChangeResponsibleIndividualToSelfCommandHandler.process(*[ApplicationData], *[ChangeResponsibleIndividualToSelf])
        ).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeResponsibleIndividualToSelfCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    " ChangeResponsibleIndividualToOther is received" should {
      val code = "someCode"
      val cmd  = ChangeResponsibleIndividualToOther(code, timestamp)
      val evt  = ResponsibleIndividualChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        "previousRIName",
        "previousRIEmail",
        "newRIName",
        "newRIEmail",
        Submission.Id.random,
        1,
        code,
        requestedByName,
        requestedByEmail
      )

      "call  ChangeResponsibleIndividualToOther Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(
          mockChangeResponsibleIndividualToOtherCommandHandler.process(*[ApplicationData], *[ChangeResponsibleIndividualToOther])
        ).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeResponsibleIndividualToOtherCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "DeclineApplicationApprovalRequest is received" should {

      val timestamp = FixedClock.now
      val actor     = Actors.GatekeeperUser(adminEmail)

      val cmd = DeclineApplicationApprovalRequest(actor.user, reasons, timestamp)
      val evt = ApplicationApprovalRequestDeclined(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        "someUserName",
        "someUserEmail",
        Submission.Id.random,
        1,
        "some reason or other",
        "adminName",
        "adminEmail"
      )

      "call DeclineApplicationApprovalRequest Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockDeclineApplicationApprovalRequestCommandHandler.process(*[ApplicationData], *[DeclineApplicationApprovalRequest])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeclineApplicationApprovalRequestCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    " DeleteApplicationByCollaborator is received" should {

      val cmd = DeleteApplicationByCollaborator(UserId.random, reasons, timestamp)
      val evt = ApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        Actors.Collaborator("someEmail"),
        ClientId.random,
        "wsoApplicationName",
        reasons
      )

      "call  DeleteApplicationByCollaborator Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockDeleteApplicationByCollaboratorCommandHandler.process(*[ApplicationData], *[DeleteApplicationByCollaborator])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeleteApplicationByCollaboratorCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "DeleteApplicationByGatekeeper is received" should {

      val cmd = DeleteApplicationByGatekeeper(gatekeeperUser, requestedByEmail, reasons, timestamp)
      val evt = ApplicationDeletedByGatekeeper(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        Actors.GatekeeperUser(gatekeeperUser),
        ClientId.random,
        "wsoApplicationName",
        reasons,
        requestedByEmail
      )

      "call  DeleteApplicationByGatekeeper Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockDeleteApplicationByGatekeeperCommandHandler.process(*[ApplicationData], *[DeleteApplicationByGatekeeper])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeleteApplicationByGatekeeperCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    " DeleteUnusedApplication is received" should {
      val authKey = "kjghhjgdasijgdkgjhsa"
      val cmd     = DeleteUnusedApplication(jobId, authKey, reasons, timestamp)
      val evt     = ApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        scheduledJobActor,
        ClientId.random,
        "wsoApplicationName",
        reasons
      )

      "call  DeleteApplicationByGatekeeper Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockDeleteUnusedApplicationCommandHandler.process(*[ApplicationData], *[DeleteUnusedApplication])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeleteUnusedApplicationCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "DeleteProductionCredentialsApplication is received" should {
      val cmd = DeleteProductionCredentialsApplication(jobId, reasons, timestamp)
      val evt = ProductionCredentialsApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        devHubUser,
        ClientId.random,
        "wsoApplicationName",
        reasons
      )

      "call  DeleteApplicationByGatekeeper Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockDeleteProductionCredentialsApplicationCommandHandler.process(*[ApplicationData], *[DeleteProductionCredentialsApplication])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeleteProductionCredentialsApplicationCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "SubscribeToApi is received" should {
      val context       = "context"
      val version       = "version"
      val apiIdentifier = ApiIdentifier(ApiContext(context), ApiVersion(version))
      val cmd           = SubscribeToApi(devHubUser, apiIdentifier, timestamp)
      val evt           = ApiSubscribed(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        devHubUser,
        context,
        version
      )

      "call SubscribeToApi Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockSubscribeToApiCommandHandler.process(*[ApplicationData], *[SubscribeToApi])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[SubscribeToApiCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "UnsubscribeFromApi is received" should {
      val context       = "context"
      val version       = "version"
      val apiIdentifier = ApiIdentifier(ApiContext(context), ApiVersion(version))
      val cmd           = UnsubscribeFromApi(devHubUser, apiIdentifier, timestamp)
      val evt           = ApiUnsubscribed(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        devHubUser,
        context,
        version
      )

      "call UnsubscribeFromApi Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockUnsubscribeFromApiCommandHandler.process(*[ApplicationData], *[UnsubscribeFromApi])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[UnsubscribeFromApiCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    " UpdateRedirectUris is received" should {
      val oldUris = List("uri1", "uri2")
      val newUris = List("uri3", "uri4")
      val cmd     = UpdateRedirectUris(devHubUser, oldUris, newUris, timestamp)
      val evt     = RedirectUrisUpdated(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        devHubUser,
        oldUris,
        newUris
      )

      "call UpdateRedirectUris Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockUpdateRedirectUrisCommandHandler.process(*[ApplicationData], *[UpdateRedirectUris])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[UpdateRedirectUrisCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }
  }
}
