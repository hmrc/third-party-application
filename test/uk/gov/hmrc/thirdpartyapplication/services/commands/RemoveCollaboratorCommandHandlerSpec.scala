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

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.RemoveCollaborator
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.CollaboratorRemovedV2
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

class RemoveCollaboratorCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new RemoveCollaboratorCommandHandler(ApplicationRepoMock.aMock)

    val applicationId = ApplicationId.random
    val anAdminEmail  = "admin@example.com".toLaxEmail

    val developerCollaborator = devEmail.developer()

    val adminCollaborator = anAdminEmail.admin()
    val adminActor        = otherAdminAsActor

    val gkUserEmail  = "admin@gatekeeper"
    val gkUserActor  = Actors.GatekeeperUser(gkUserEmail)
    val unknownEmail = "someEmail"

    val jobId             = "theJobThatDeletesCollaborators"
    val scheduledJobActor = Actors.ScheduledJob(jobId)
    val collaboratorEmail = "newdev@somecompany.com"

    val collaborator = collaboratorEmail.developer()

    val app = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator,
        collaborator
      )
    )

    val timestamp = FixedClock.instant

    val adminsToEmail = Set(anAdminEmail, devEmail)

    val removeCollaborator = RemoveCollaborator(Actors.AppCollaborator(adminActor.email), collaborator, now)

    def checkSuccessResult(expectedActor: Actor)(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case CollaboratorRemovedV2(_, appId, eventDateTime, actor, evtCollaborator) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            evtCollaborator shouldBe collaborator
        }
      }
    }
  }

  "RemoveCollaborator" should {
    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.RemoveCollaborator.succeeds(app)

      val result = await(underTest.process(app, removeCollaborator.copy(actor = gkUserActor)).value).value

      checkSuccessResult(gkUserActor)(result)
    }

    "succeed as Actors.AppCollaborator" in new Setup {
      ApplicationRepoMock.RemoveCollaborator.succeeds(app)

      val result = await(underTest.process(app, removeCollaborator).value).value

      checkSuccessResult(adminActor)(result)
    }

    "return an error when collaborator is not associated to the application" in new Setup {
      val removeUnknownCollaboratorCommand = removeCollaborator.copy(collaborator = unknownEmail.developer())

      checkFailsWith(CommandFailures.CollaboratorDoesNotExistOnApp) {
        underTest.process(app, removeUnknownCollaboratorCommand)
      }
    }

    "return an error when actor is collaborator actor and is not associated to the application" in new Setup {
      val removeCollaboratorActorUnknownCommand: RemoveCollaborator = removeCollaborator.copy(actor = Actors.AppCollaborator(unknownEmail.toLaxEmail))

      checkFailsWith(CommandFailures.ActorIsNotACollaboratorOnApp) {
        underTest.process(app, removeCollaboratorActorUnknownCommand)
      }
    }

    "return an error when collaborator is last admin associated with the application" in new Setup {
      val removeLastAdminCollaboratorCommand = removeCollaborator.copy(collaborator = adminCollaborator)

      checkFailsWith(CommandFailures.CannotRemoveLastAdmin) {
        underTest.process(app, removeLastAdminCollaboratorCommand)
      }
    }
  }
}
