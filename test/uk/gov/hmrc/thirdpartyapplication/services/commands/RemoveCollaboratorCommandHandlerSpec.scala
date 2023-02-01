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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{Actor, CollaboratorActor, CollaboratorRemoved, GatekeeperUserActor, ScheduledJobActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}

import scala.concurrent.ExecutionContext.Implicits.global

class RemoveCollaboratorCommandHandlerSpec extends AsyncHmrcSpec
    with ApplicationRepositoryMockModule
    with ApplicationTestData {

  trait Setup {
    val underTest = new RemoveCollaboratorCommandHandler(ApplicationRepoMock.aMock)

    val applicationId = ApplicationId.random
    val adminEmail    = "admin@example.com"

    val developerCollaborator = Collaborator(devEmail, Role.DEVELOPER, idOf(devEmail))

    val adminCollaborator = Collaborator(adminEmail, Role.ADMINISTRATOR, idOf(adminEmail))
    val adminActor        = CollaboratorActor(adminEmail)

    val gkUserEmail  = "admin@gatekeeper"
    val gkUserActor  = GatekeeperUserActor(gkUserEmail)
    val unknownEmail = "someEmail"

    val jobId             = "theJobThatDeletesCollaborators"
    val scheduledJobActor = ScheduledJobActor(jobId)
    val collaboratorEmail = "newdev@somecompany.com"

    val collaborator = Collaborator(collaboratorEmail, Role.DEVELOPER, idOf(collaboratorEmail))

    val app = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator,
        collaborator
      )
    )

    val timestamp = FixedClock.now

    val adminsToEmail = Set(adminEmail, devEmail)

    val removeCollaborator = RemoveCollaborator(CollaboratorActor(adminActor.email), collaborator, adminsToEmail, timestamp)

    def checkSuccessResult(expectedActor: Actor)(result: CommandHandler2.CommandSuccess) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case CollaboratorRemoved(_, appId, eventDateTime, actor, collaboratorId, collaboratorEmail, collaboratorRole, notifyCollaborator: Boolean, verifiedAdminsToEmail) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            collaboratorId shouldBe collaborator.userId
            collaboratorRole shouldBe collaborator.role
            collaboratorEmail shouldBe collaborator.emailAddress
            notifyCollaborator shouldBe app.collaborators.contains(removeCollaborator.collaborator)
            verifiedAdminsToEmail shouldBe removeCollaborator.adminsToEmail
        }
      }
    }

  }

  "RemoveCollaborator" should {
    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.RemoveCollaborator.succeeds(app)

      val result = await(underTest.process(app, removeCollaborator.copy(actor = gkUserActor)).value).right.value

      checkSuccessResult(gkUserActor)(result)
    }

    "succeed as collaboratorActor" in new Setup {
      ApplicationRepoMock.RemoveCollaborator.succeeds(app)

      val result = await(underTest.process(app, removeCollaborator).value).right.value

      checkSuccessResult(adminActor)(result)
    }

    "return an error when collaborator is not associated to the application" in new Setup {

      val removeUnknownCollaboratorCommand = removeCollaborator.copy(collaborator = Collaborator(unknownEmail, Role.DEVELOPER, idOf(unknownEmail)))

      val result = await(underTest.process(app, removeUnknownCollaboratorCommand).value).left.value.toNonEmptyList.toList

      result should have length 1
      result.head shouldBe s"no collaborator found with email: $unknownEmail"
    }

    "return an error when actor is collaborator actor and is not associated to the application" in new Setup {

      val removeCollaboratorActorUnknownCommand: RemoveCollaborator = removeCollaborator.copy(actor = CollaboratorActor(unknownEmail))

      val result = await(underTest.process(app, removeCollaboratorActorUnknownCommand).value).left.value.toNonEmptyList.toList

      result should have length 1
      result.head shouldBe s"no collaborator found with email: $unknownEmail"
    }

    "return an error when collaborator is last admin associated with the application" in new Setup {

      val removeLastAdminCollaboratorCommand = removeCollaborator.copy(collaborator = adminCollaborator)

      val result = await(underTest.process(app, removeLastAdminCollaboratorCommand).value).left.value.toNonEmptyList.toList

      result should have length 1
      result.head shouldBe s"Collaborator is last remaining admin for Application ${applicationId.value}"
    }

  }
}
