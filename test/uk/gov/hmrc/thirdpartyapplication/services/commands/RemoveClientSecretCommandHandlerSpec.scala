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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ClientSecretRemovedV2
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

class RemoveClientSecretCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new RemoveClientSecretCommandHandler(ApplicationRepoMock.aMock)

    val developerCollaborator = devEmail.developer(developerUserId)
    val adminCollaborator     = anAdminEmail.admin(adminUserId)

    val applicationId = ApplicationId.random

    val principalApp   = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator
      )
    )
    val subordinateApp = principalApp.copy(environment = Environment.SANDBOX.toString())

    val timestamp    = FixedClock.instant
    val secretValue  = "secret"
    val clientSecret = principalApp.tokens.production.clientSecrets.head

    val removeClientSecretByDev   = RemoveClientSecret(Actors.AppCollaborator(devEmail), clientSecret.id, FixedClock.now)
    val removeClientSecretByAdmin = RemoveClientSecret(otherAdminAsActor, clientSecret.id, FixedClock.now)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ClientSecretRemovedV2(_, appId, eventDateTime, actor, clientSecretId, clientSecretName) =>
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
      checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
        underTest.process(principalApp, removeClientSecretByDev)
      }
    }

    "return an error for an admin where the client secret id is not valid" in new Setup {
      val invalidCommand = removeClientSecretByAdmin.copy(clientSecretId = "invalid")
      
      checkFailsWith(s"Client Secret Id invalid not found in Application ${principalApp.id.value}") {
        underTest.process(principalApp, invalidCommand)
      }
    }

    "return errors for a non-admin developer where the client secret id is not valid" in new Setup {
      val invalidCommand = removeClientSecretByDev.copy(clientSecretId = "invalid")
      
      checkFailsWith("App is in PRODUCTION so User must be an ADMIN", s"Client Secret Id invalid not found in Application ${principalApp.id.value}") {
        underTest.process(principalApp, invalidCommand)
      }
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
