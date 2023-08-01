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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientSecret
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.AddClientSecret
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ClientSecretAddedV2
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.CredentialConfig

class AddClientSecretCommandHandlerSpec extends CommandHandlerBaseSpec {

  class Setup(limit: Int = 3) extends ApplicationRepositoryMockModule {
    val config    = CredentialConfig(limit)
    val underTest = new AddClientSecretCommandHandler(ApplicationRepoMock.aMock, config)

    val timestamp   = FixedClock.instant
    val secretValue = "secret"
    val id          = ClientSecret.Id.random

    val addClientSecretByDev   = AddClientSecret(developerActor, "name", id, "hashed", now)
    val addClientSecretByAdmin = AddClientSecret(otherAdminAsActor, "name", id, "hashed", now)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ClientSecretAddedV2(_, appId, eventDateTime, actor, clientSecretId, clientSecretName) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            clientSecretId shouldBe id.value.toString
            clientSecretName shouldBe "name"
        }
      }
    }
  }

  "AddClientSecretCommandHandler" when {
    "given a principal application" should {
      "succeed for an admin" in new Setup {
        val updatedApp = principalApp // Don't need the ClientSecrets fixed here
        ApplicationRepoMock.AddClientSecret.thenReturn(applicationId)(updatedApp)

        val result = await(underTest.process(principalApp, addClientSecretByAdmin).value).value

        checkSuccessResult(adminActor)(result)
      }

      "return an error for a non-admin developer" in new Setup {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
          underTest.process(principalApp, addClientSecretByDev)
        }
      }

      "return an error for a non-admin developer and application with full secrets" in new Setup(1) {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN", "Client secret limit has been exceeded") {
          underTest.process(principalApp, addClientSecretByDev)
        }
      }
    }

    "given a subordinate application" should {
      "succeed for a developer" in new Setup {
        ApplicationRepoMock.AddClientSecret.thenReturn(applicationId)(subordinateApp)

        val result = await(underTest.process(subordinateApp, addClientSecretByDev).value).value

        checkSuccessResult(developerActor)(result)
      }
    }
  }
}
