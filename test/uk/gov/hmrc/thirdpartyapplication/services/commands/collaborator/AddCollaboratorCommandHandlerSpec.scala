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

package uk.gov.hmrc.thirdpartyapplication.services.commands.collaborator

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.AddCollaborator
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class AddCollaboratorCommandHandlerSpec extends CommandHandlerBaseSpec {

  import CommandFailures._

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new AddCollaboratorCommandHandler(ApplicationRepoMock.aMock)

    val timestamp = FixedClock.instant

    val newCollaboratorEmail = "newdev@somecompany.com"
    val newCollaborator      = newCollaboratorEmail.developer()

    val adminsToEmail = Set(adminOne.emailAddress, developerOne.emailAddress)

    val addCollaboratorAsAdmin = AddCollaborator(adminActor, newCollaborator, instant)
    val addCollaboratorAsDev   = AddCollaborator(developerActor, newCollaborator, instant)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(fn: => CommandHandler.AppCmdResultT) = {
      val testThis = await(fn.value).value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.CollaboratorAddedV2(_, appId, eventDateTime, actor, collaborator) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            collaborator shouldBe newCollaborator
        }
      }
    }
  }

  "given a principal application" should {
    "succeed for an admin" in new Setup {
      ApplicationRepoMock.AddCollaborator.succeeds(principalApp) // Not modified

      checkSuccessResult(adminActor)(underTest.process(principalApp, addCollaboratorAsAdmin))
    }

    "succeed for a non-admin developer" in new Setup {
      ApplicationRepoMock.AddCollaborator.succeeds(principalApp) // Not modified

      checkSuccessResult(developerActor)(underTest.process(principalApp, addCollaboratorAsDev))
    }

    "return an error when collaborate already exists on the app" in new Setup {
      val existingCollaboratorCmd = addCollaboratorAsAdmin.copy(collaborator = otherAdminCollaborator)

      checkFailsWith(CollaboratorAlreadyExistsOnApp) {
        underTest.process(principalApp, existingCollaboratorCmd)
      }
    }
  }

  "given a subordinate application" should {
    "succeed for a non-admin developer" in new Setup {
      ApplicationRepoMock.AddCollaborator.succeeds(subordinateApp) // Not modified

      checkSuccessResult(developerActor)(underTest.process(subordinateApp, addCollaboratorAsDev))
    }
  }
}
