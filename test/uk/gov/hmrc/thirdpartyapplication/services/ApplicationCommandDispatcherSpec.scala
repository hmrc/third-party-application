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

import scala.concurrent.ExecutionContext.Implicits.global
import cats.data._
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler2.CommandFailures
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationCommandDispatcherUtils
import uk.gov.hmrc.thirdpartyapplication.util._

import java.util.UUID


class ApplicationCommandDispatcherSpec extends ApplicationCommandDispatcherUtils {

  trait Setup extends CommonSetup {
    val applicationData: ApplicationData = anApplicationData(applicationId)

    def primeCommonServiceSuccess() ={

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
        .applyEvents(eqTo(applicationData),eqTo(NonEmptyList.one(expectedEvent)))(*[HeaderCarrier])

      expectedEvent match {
        case e:  UpdateApplicationEvent with TriggersNotification => verify(NotificationServiceMock.aMock)
          .sendNotifications(eqTo(applicationData), eqTo(List(e)))(*[HeaderCarrier])
        case _ => succeed
      }

    }
  }

    val timestamp = FixedClock.now
    val gatekeeperUser = "gkuser1"
    val adminName = "Mr Admin"
    val devHubUser = CollaboratorActor(adminEmail)
    val applicationId = ApplicationId.random

    val E = EitherTHelper.make[CommandFailures]



 "dispatch" when {
   "AddClientSecret is received" should {
     val clientSecret = ClientSecret("name", FixedClock.now, None, UUID.randomUUID().toString, "hashedSecret")
     val cmd: AddClientSecret = AddClientSecret(devHubUser, clientSecret,  FixedClock.now)
     val evt: ClientSecretAddedV2 = ClientSecretAddedV2(UpdateApplicationEvent.Id.random, applicationId, FixedClock.now, devHubUser, clientSecret.name, clientSecret.id)

     "call AddClientSecretCommand Handler and relevant common services if application exists" in new Setup {
         ApplicationRepoMock.Fetch.thenReturn(applicationData)
       primeCommonServiceSuccess()

       when(mockAddClientSecretCommandHandler.process(*[ApplicationData], *[AddClientSecret]))
         .thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

       await(underTest.dispatch(applicationId, cmd).value)
       verifyServicesCalledWithEvent(evt)

     }

     "bubble up exception when application fetch fails" in new Setup {
       ApplicationRepoMock.Fetch.thenFail(new RuntimeException("some error"))

       intercept[RuntimeException]{
         await(underTest.dispatch(applicationId, cmd).value)
       }
       verifyZeroInteractions(mockAddClientSecretCommandHandler)
       verifyNoCommonServicesCalled
     }
   }


   "RemoveClientSecret is received" should {
     val cmd: RemoveClientSecret = RemoveClientSecret(devHubUser, UUID.randomUUID().toString, FixedClock.now)
     val evt: ClientSecretRemoved = ClientSecretRemoved(UpdateApplicationEvent.Id.random, applicationId, FixedClock.now, devHubUser, cmd.clientSecretId, "someName")

     "call RemoveClientSecretCommand Handler and relevant common services if application exists" in new Setup {
       ApplicationRepoMock.Fetch.thenReturn(applicationData)
       primeCommonServiceSuccess()

       when(mockRemoveClientSecretCommandHandler.process(*[ApplicationData], *[RemoveClientSecret]))
         .thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

       await(underTest.dispatch(applicationId, cmd).value)
       verify(mockRemoveClientSecretCommandHandler).process(*[ApplicationData], *[RemoveClientSecret])
       verifyServicesCalledWithEvent(evt)

     }

     "bubble up exception when application fetch fails" in new Setup {
       ApplicationRepoMock.Fetch.thenFail(new RuntimeException("some error"))

       intercept[RuntimeException] {
         await(underTest.dispatch(applicationId, cmd).value)
       }
       verifyZeroInteractions(mockRemoveClientSecretCommandHandler)
       verifyNoCommonServicesCalled
     }

   }


   "AddCollaborator is received" should {
     val collaborator = Collaborator("email", Role.DEVELOPER, UserId.random)
     val adminsToEmail = Set("email1", "email2")
     val cmd: AddCollaborator = AddCollaborator(devHubUser,collaborator,  adminsToEmail, FixedClock.now)
     val evt: CollaboratorAdded = CollaboratorAdded(UpdateApplicationEvent.Id.random,
       applicationId,
       FixedClock.now,
       devHubUser,
       collaborator.userId,
       collaborator.emailAddress,
       collaborator.role,
       adminsToEmail)

     "call AddCollaboratorCommand Handler and relevant common services if application exists" in new Setup {
       ApplicationRepoMock.Fetch.thenReturn(applicationData)
       primeCommonServiceSuccess()

       when(mockAddCollaboratorCommandHandler.process(*[ApplicationData], *[AddCollaborator]))
         .thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

       await(underTest.dispatch(applicationId, cmd).value)
       verifyServicesCalledWithEvent(evt)

     }

     "bubble up exception when application fetch fails" in new Setup {
       ApplicationRepoMock.Fetch.thenFail(new RuntimeException("some error"))

       intercept[RuntimeException] {
         await(underTest.dispatch(applicationId, cmd).value)
       }
       verifyZeroInteractions(mockAddCollaboratorCommandHandler)
       verifyNoCommonServicesCalled
     }

   }

   "RemoveCollaborator is received" should {

     val collaborator = Collaborator("email", Role.DEVELOPER, UserId.random)
     val adminsToEmail = Set("email1", "email2")
     val cmd: RemoveCollaborator = RemoveCollaborator(devHubUser, collaborator, adminsToEmail, FixedClock.now)
     val evt: CollaboratorRemoved = CollaboratorRemoved(UpdateApplicationEvent.Id.random,
       applicationId,
       FixedClock.now,
       devHubUser,
       collaborator.userId,
       collaborator.emailAddress,
       collaborator.role,
       notifyCollaborator = true,
       adminsToEmail)

     "call RemoveCollaboratorCommand Handler and relevant common services if application exists" in new Setup {
       ApplicationRepoMock.Fetch.thenReturn(applicationData)
       primeCommonServiceSuccess()

       when(mockRemoveCollaboratorCommandHandler.process(*[ApplicationData], *[RemoveCollaborator]))
         .thenReturn(E.pure((applicationData, NonEmptyList.one(evt))))

       await(underTest.dispatch(applicationId, cmd).value)
       verifyServicesCalledWithEvent(evt)

     }

     "bubble up exception when application fetch fails" in new Setup {
       ApplicationRepoMock.Fetch.thenFail(new RuntimeException("some error"))

       intercept[RuntimeException] {
         await(underTest.dispatch(applicationId, cmd).value)
       }
       verifyZeroInteractions(mockRemoveCollaboratorCommandHandler)
       verifyNoCommonServicesCalled
     }

   }
 }

}
