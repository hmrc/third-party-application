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

import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

import cats.data._

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ClientId, ClientSecret, PrivacyPolicyLocations, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommand
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, ApplicationEvents, EventId}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.{AddClientSecretCommandHandler, _}
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationCommandDispatcherUtils

class ApplicationCommandDispatcherSpec
    extends ApplicationCommandDispatcherUtils
    with CommandApplicationExamples
    with FixedClock {

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

    def verifyServicesCalledWithEvent(expectedEvent: ApplicationEvent) = {

      verify(ApiPlatformEventServiceMock.aMock)
        .applyEvents(*[NonEmptyList[ApplicationEvent]])(*[HeaderCarrier])

      verify(AuditServiceMock.aMock)
        .applyEvents(eqTo(applicationData), eqTo(NonEmptyList.one(expectedEvent)))(*[HeaderCarrier])

      verify(NotificationServiceMock.aMock)
        .sendNotifications(eqTo(applicationData), eqTo(NonEmptyList.one(expectedEvent)), *)(*[HeaderCarrier])
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
        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
      }

      allCommandHandlers.foreach(handler => verifyZeroInteractions(handler))

      verifyNoCommonServicesCalled()
    }
  }

  val timestamp         = now
  val jobId             = "jobId"
  val scheduledJobActor = Actors.ScheduledJob(jobId)
  val reasons           = "some reason or other"

  val E = EitherTHelper.make[CommandHandler.Failures]

  "dispatch" when {
    "AddClientSecret is received" should {
      val id                                         = ClientSecret.Id.random
      val cmd: AddClientSecret                       = AddClientSecret(otherAdminAsActor, "name", id, "hashedSecret", now)
      val evt: ApplicationEvents.ClientSecretAddedV2 = ApplicationEvents.ClientSecretAddedV2(EventId.random, applicationId, instant, otherAdminAsActor, "name", id.value.toString)

      "call AddClientSecretCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockAddClientSecretCommandHandler.process(*[ApplicationData], *[AddClientSecret])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[AddClientSecretCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }
    }

    "RemoveClientSecret is received" should {
      val cmd: RemoveClientSecret                      = RemoveClientSecret(otherAdminAsActor, ClientSecret.Id.random, now)
      val evt: ApplicationEvents.ClientSecretRemovedV2 =
        ApplicationEvents.ClientSecretRemovedV2(EventId.random, applicationId, instant, otherAdminAsActor, cmd.clientSecretId.value.toString(), "someName")

      "call RemoveClientSecretCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockRemoveClientSecretCommandHandler.process(*[ApplicationData], *[RemoveClientSecret])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[RemoveClientSecretCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "AddCollaborator is received" should {
      val collaborator                               = "email".developer()
      val adminsToEmail                              = Set("email1".toLaxEmail, "email2".toLaxEmail)
      val cmd: AddCollaborator                       = AddCollaborator(otherAdminAsActor, collaborator, now)
      val evt: ApplicationEvents.CollaboratorAddedV2 = ApplicationEvents.CollaboratorAddedV2(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        collaborator
      )

      "call AddCollaboratorCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockAddCollaboratorCommandHandler.process(*[ApplicationData], *[AddCollaborator])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[AddCollaboratorCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "RemoveCollaborator is received" should {

      val collaborator                                 = "email".developer()
      val adminsToEmail                                = Set("email1".toLaxEmail, "email2".toLaxEmail)
      val cmd: RemoveCollaborator                      = RemoveCollaborator(otherAdminAsActor, collaborator, now)
      val evt: ApplicationEvents.CollaboratorRemovedV2 = ApplicationEvents.CollaboratorRemovedV2(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        collaborator
      )

      "call RemoveCollaboratorCommand Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockRemoveCollaboratorCommandHandler.process(*[ApplicationData], *[RemoveCollaborator])).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
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
      val requester      = "requester".toLaxEmail
      val actor          = Actors.GatekeeperUser(gatekeeperUser)
      val userId         = UserId.random

      val timestamp = now
      val cmd       = ChangeProductionApplicationName(gatekeeperUser, userId, timestamp, newName)
      val evt       = ApplicationEvents.ProductionAppNameChangedEvent(
        EventId.random,
        applicationId,
        instant,
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

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
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
      val userId      = idOf(anAdminEmail)
      val timestamp   = now
      val actor       = otherAdminAsActor

      val cmd = ChangeProductionApplicationPrivacyPolicyLocation(userId, timestamp, newLocation)
      val evt = ApplicationEvents.ProductionAppPrivacyPolicyLocationChanged(
        EventId.random,
        applicationId,
        instant,
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

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
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
      val userId      = idOf(anAdminEmail)
      val timestamp   = now
      val actor       = otherAdminAsActor

      val cmd = ChangeProductionApplicationTermsAndConditionsLocation(userId, timestamp, newLocation)
      val evt = ApplicationEvents.ProductionAppTermsConditionsLocationChanged(
        EventId.random,
        applicationId,
        instant,
        actor,
        termsAndConditionsLocation,
        newLocation
      )

      "call ChangeProductionApplicationTermsAndConditionsLocation Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(
          mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationTermsAndConditionsLocation])
        ).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeProductionApplicationTermsAndConditionsLocationCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "ChangeResponsibleIndividualToSelf is received" should {

      val cmd = ChangeResponsibleIndividualToSelf(UserId.random, timestamp, requestedByName, requestedByEmail)
      val evt = ApplicationEvents.ResponsibleIndividualChangedToSelf(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        "previousRIName",
        "previousRIEmail".toLaxEmail,
        SubmissionId(SubmissionId.random.value),
        1,
        requestedByName,
        requestedByEmail
      )

      "call  ChangeResponsibleIndividualToSelf Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(
          mockChangeResponsibleIndividualToSelfCommandHandler.process(*[ApplicationData], *[ChangeResponsibleIndividualToSelf])
        ).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeResponsibleIndividualToSelfCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "ChangeResponsibleIndividualToOther is received" should {
      val code = "someCode"
      val cmd  = ChangeResponsibleIndividualToOther(code, timestamp)
      val evt  = ApplicationEvents.ResponsibleIndividualChanged(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        "previousRIName",
        "previousRIEmail".toLaxEmail,
        "newRIName",
        "newRIEmail".toLaxEmail,
        SubmissionId(SubmissionId.random.value),
        1,
        code,
        requestedByName,
        requestedByEmail
      )

      "call ChangeResponsibleIndividualToOther Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(
          mockChangeResponsibleIndividualToOtherCommandHandler.process(*[ApplicationData], *[ChangeResponsibleIndividualToOther])
        ).thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[ChangeResponsibleIndividualToOtherCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "DeclineApplicationApprovalRequest is received" should {

      val timestamp = now
      val actor     = Actors.GatekeeperUser(gatekeeperUser)

      val cmd = DeclineApplicationApprovalRequest(actor.user, reasons, timestamp)
      val evt = ApplicationEvents.ApplicationApprovalRequestDeclined(
        EventId.random,
        applicationId,
        instant,
        actor,
        "someUserName",
        "someUserEmail".toLaxEmail,
        SubmissionId(SubmissionId.random.value),
        1,
        "some reason or other",
        "adminName",
        "anAdminEmail".toLaxEmail
      )

      "call DeclineApplicationApprovalRequest Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockDeclineApplicationApprovalRequestCommandHandler.process(*[ApplicationData], *[DeclineApplicationApprovalRequest])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeclineApplicationApprovalRequestCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    " DeleteApplicationByCollaborator is received" should {

      val cmd = DeleteApplicationByCollaborator(UserId.random, reasons, timestamp)
      val evt = ApplicationEvents.ApplicationDeleted(
        EventId.random,
        applicationId,
        instant,
        Actors.AppCollaborator("someEmail".toLaxEmail),
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

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeleteApplicationByCollaboratorCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "DeleteApplicationByGatekeeper is received" should {

      val cmd = DeleteApplicationByGatekeeper(gatekeeperUser, requestedByEmail, reasons, timestamp)
      val evt = ApplicationEvents.ApplicationDeletedByGatekeeper(
        EventId.random,
        applicationId,
        instant,
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

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
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
      val evt     = ApplicationEvents.ApplicationDeleted(
        EventId.random,
        applicationId,
        instant,
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

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeleteUnusedApplicationCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "DeleteProductionCredentialsApplication is received" should {
      val cmd = DeleteProductionCredentialsApplication(jobId, reasons, timestamp)
      val evt = ApplicationEvents.ProductionCredentialsApplicationDeleted(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
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

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[DeleteProductionCredentialsApplicationCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "SubscribeToApi is received" should {
      val context       = ApiContext("context")
      val version       = ApiVersion("version")
      val apiIdentifier = ApiIdentifier(context, version)
      val cmd           = SubscribeToApi(otherAdminAsActor, apiIdentifier, timestamp)
      val evt           = ApplicationEvents.ApiSubscribedV2(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        context,
        version
      )

      "call SubscribeToApi Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockSubscribeToApiCommandHandler.process(*[ApplicationData], *[SubscribeToApi])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[SubscribeToApiCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }

    "UnsubscribeFromApi is received" should {
      val context       = ApiContext("context")
      val version       = ApiVersion("version")
      val apiIdentifier = ApiIdentifier(context, version)
      val cmd           = UnsubscribeFromApi(otherAdminAsActor, apiIdentifier, timestamp)
      val evt           = ApplicationEvents.ApiUnsubscribedV2(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        context,
        version
      )

      "call UnsubscribeFromApi Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockUnsubscribeFromApiCommandHandler.process(*[ApplicationData], *[UnsubscribeFromApi])(*[HeaderCarrier])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
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
      val cmd     = UpdateRedirectUris(otherAdminAsActor, oldUris, newUris, timestamp)
      val evt     = ApplicationEvents.RedirectUrisUpdatedV2(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        oldUris,
        newUris
      )

      "call UpdateRedirectUris Handler and relevant common services if application exists" in new Setup {
        primeCommonServiceSuccess()

        when(mockUpdateRedirectUrisCommandHandler.process(*[ApplicationData], *[UpdateRedirectUris])).thenReturn(E.pure((
          applicationData,
          NonEmptyList.one(evt)
        )))

        await(underTest.dispatch(applicationId, cmd, Set.empty).value)
        verifyServicesCalledWithEvent(evt)
        verifyNoneButGivenCmmandHandlerCalled[UpdateRedirectUrisCommandHandler]()
      }

      "bubble up exception when application fetch fails" in new Setup {
        testFailure(cmd)
      }

    }
  }
}
