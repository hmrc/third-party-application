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

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

class AddCollaboratorCommandHandlerSpec
    extends AsyncHmrcSpec
    with ApplicationTestData
    with CommandActorExamples
    with CommandCollaboratorExamples
    with CommandApplicationExamples {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new AddCollaboratorCommandHandler(ApplicationRepoMock.aMock)

    val timestamp = FixedClock.now

    val newCollaboratorEmail = "newdev@somecompany.com"
    val newCollaborator      = Collaborator(newCollaboratorEmail, Role.DEVELOPER, idOf(newCollaboratorEmail))

    val adminsToEmail = Set(adminEmail, devEmail)

    val addCollaboratorAsAdmin = AddCollaborator(adminActor, newCollaborator, adminsToEmail, timestamp)
    val addCollaboratorAsDev   = AddCollaborator(developerActor, newCollaborator, adminsToEmail, timestamp)

    def checkSuccessResult(expectedActor: CollaboratorActor)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).right.value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case CollaboratorAdded(_, appId, eventDateTime, actor, collaboratorId, collaboratorEmail, collaboratorRole, verifiedAdminsToEmail) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            Collaborator(collaboratorEmail, collaboratorRole, collaboratorId) shouldBe newCollaborator
        }
      }
    }

    def checkFailsWith(msg: String)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1
      testThis.head shouldBe msg
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
      val existingCollaboratorCmd = addCollaboratorAsAdmin.copy(collaborator = adminCollaborator)

      checkFailsWith(s"Collaborator already linked to Application ${applicationId.value}") {
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
