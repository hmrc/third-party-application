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

package uk.gov.hmrc.thirdpartyapplication.services.commands.clientsecret

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, ClientSecret}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.RemoveClientSecret
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ClientSecretRemovedV2
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class RemoveClientSecretCommandHandlerSpec extends CommandHandlerBaseSpec with ApplicationWithCollaboratorsFixtures {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new RemoveClientSecretCommandHandler(ApplicationRepoMock.aMock)

    val principalApp   = storedApp.copy(
      collaborators = Set(adminOne, developerOne)
    )
    val subordinateApp = principalApp.inSandbox()

    val secretValue  = "secret"
    val clientSecret = principalApp.tokens.production.clientSecrets.head

    val removeClientSecretByDev   = RemoveClientSecret(Actors.AppCollaborator(developerOne.emailAddress), clientSecret.id, instant)
    val removeClientSecretByAdmin = RemoveClientSecret(otherAdminAsActor, clientSecret.id, instant)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ClientSecretRemovedV2(_, appId, eventDateTime, actor, clientSecretId, clientSecretName) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe instant
            clientSecretId shouldBe clientSecret.id.value.toString()
            clientSecretName shouldBe clientSecret.name
        }
      }
    }

  }

  "given a principal application" should {
    "succeed for an admin" in new Setup {
      ApplicationRepoMock.DeleteClientSecret.succeeds(principalApp, clientSecret.id)

      val result = await(underTest.process(principalApp, removeClientSecretByAdmin).value).value

      checkSuccessResult(adminActor)(result)
    }

    "return an error for a non-admin developer" in new Setup {
      checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
        underTest.process(principalApp, removeClientSecretByDev)
      }
    }

    "return an error for an admin where the client secret id is not valid" in new Setup {
      val invalidCommand = removeClientSecretByAdmin.copy(clientSecretId = ClientSecret.Id.random)

      checkFailsWith(s"Client Secret Id ${invalidCommand.clientSecretId.value} not found in Application ${principalApp.id.value}") {
        underTest.process(principalApp, invalidCommand)
      }
    }

    "return errors for a non-admin developer where the client secret id is not valid" in new Setup {
      val invalidCommand = removeClientSecretByDev.copy(clientSecretId = ClientSecret.Id.random)

      checkFailsWith(
        "App is in PRODUCTION so User must be an ADMIN",
        s"Client Secret Id ${invalidCommand.clientSecretId.value} not found in Application ${principalApp.id.value}"
      ) {
        underTest.process(principalApp, invalidCommand)
      }
    }
  }

  "given a subordinate application" should {
    "succeed for a non admin" in new Setup {
      ApplicationRepoMock.DeleteClientSecret.succeeds(subordinateApp, clientSecret.id)

      val result = await(underTest.process(subordinateApp, removeClientSecretByDev).value).value

      checkSuccessResult(developerActor)(result)
    }
  }
}
