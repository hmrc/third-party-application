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

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.ClientSecretRemoved
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandActorExamples, CommandHandler, RemoveClientSecretCommandHandler}
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors

class RemoveClientSecretCommandHandlerSpec
    extends AsyncHmrcSpec
    with ApplicationTestData
    with ApplicationRepositoryMockModule
    with CommandActorExamples {

  trait Setup {
    val underTest = new RemoveClientSecretCommandHandler(ApplicationRepoMock.aMock)

    val developerCollaborator = Collaborator(devEmail, Role.DEVELOPER, developerUserId)
    val adminCollaborator     = Collaborator(adminEmail, Role.ADMINISTRATOR, adminUserId)

    val applicationId = ApplicationId.random

    val principalApp   = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator
      )
    )
    val subordinateApp = principalApp.copy(environment = Environment.SANDBOX.toString())

    val timestamp    = FixedClock.now
    val secretValue  = "secret"
    val clientSecret = principalApp.tokens.production.clientSecrets.head

    val removeClientSecretByDev   = RemoveClientSecret(Actors.Collaborator(devEmail), clientSecret.id, timestamp)
    val removeClientSecretByAdmin = RemoveClientSecret(Actors.Collaborator(adminEmail), clientSecret.id, timestamp)

    def checkSuccessResult(expectedActor: Actors.Collaborator)(result: CommandHandler.CommandSuccess) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ClientSecretRemoved(_, appId, eventDateTime, actor, clientSecretId, clientSecretName) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            clientSecretId shouldBe clientSecret.id
            clientSecretName shouldBe clientSecret.name
        }
      }
    }
  }

  "given a principal application" should {
    "succeed for an admin" in new Setup {
      ApplicationRepoMock.DeleteClientSecret.succeeds(principalApp, clientSecret.id)

      val result = await(underTest.process(principalApp, removeClientSecretByAdmin).value).right.value

      checkSuccessResult(adminActor)(result)
    }

    "return an error for a non-admin developer" in new Setup {
      val result = await(underTest.process(principalApp, removeClientSecretByDev).value).left.value.toNonEmptyList.toList

      result should have length 1
      result.head shouldBe "App is in PRODUCTION so User must be an ADMIN"
    }

    "return an error for an admin where the client secret id is not valid" in new Setup {
      val invalidCommand = removeClientSecretByAdmin.copy(clientSecretId = "invalid")
      val result         = await(underTest.process(principalApp, invalidCommand).value).left.value.toNonEmptyList.toList

      result should have length 1
      result should contain only (
        s"Client Secret Id invalid not found in Application ${principalApp.id.value}"
      )
    }

    "return errors for a non-admin developer where the client secret id is not valid" in new Setup {
      val invalidCommand = removeClientSecretByDev.copy(clientSecretId = "invalid")
      val result         = await(underTest.process(principalApp, invalidCommand).value).left.value.toNonEmptyList.toList

      result should have length 2
      result should contain allOf (
        "App is in PRODUCTION so User must be an ADMIN",
        s"Client Secret Id invalid not found in Application ${principalApp.id.value}",
      )
    }
  }

  "given a subordinate application" should {
    "succeed for a non admin" in new Setup {
      ApplicationRepoMock.DeleteClientSecret.succeeds(subordinateApp, clientSecret.id)

      val result = await(underTest.process(subordinateApp, removeClientSecretByDev).value).right.value

      checkSuccessResult(developerActor)(result)
    }
  }
}
