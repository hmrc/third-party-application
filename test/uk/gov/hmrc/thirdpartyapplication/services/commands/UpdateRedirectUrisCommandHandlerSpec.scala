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

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UpdateRedirectUris
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, Environment}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.RedirectUrisUpdatedV2
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._

class UpdateRedirectUrisCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new UpdateRedirectUrisCommandHandler(ApplicationRepoMock.aMock)

    val applicationId                    = ApplicationId.random
    val applicationData: ApplicationData = anApplicationData(applicationId)
    val subordinateApp                   = applicationData.copy(environment = Environment.SANDBOX.toString())

    val nonStandardAccessApp = applicationData.copy(access = Privileged())
    val developer            = applicationData.collaborators.head
    val developerActor       = Actors.AppCollaborator(developer.emailAddress)

    val oldRedirectUris = List.empty
    val newRedirectUris = List("https://new-url.example.com", "https://new-url.example.com/other-redirect")

    val timestamp  = FixedClock.instant
    val cmdAsAdmin = UpdateRedirectUris(adminActor, oldRedirectUris, newRedirectUris, now)
    val cmdAsDev   = UpdateRedirectUris(developerActor, oldRedirectUris, newRedirectUris, now)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case RedirectUrisUpdatedV2(_, appId, eventDateTime, actor, oldUris, newUris) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            oldUris shouldBe oldRedirectUris
            newUris shouldBe newRedirectUris
        }
      }
    }
  }

  "UpdateRedirectUrisCommandHandler" when {
    "given a principal application" should {
      "succeed when application is standardAccess" in new Setup {
        ApplicationRepoMock.UpdateRedirectUris.thenReturn(newRedirectUris)(applicationData) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(applicationData, cmdAsAdmin).value).value

        checkSuccessResult(adminActor)(result)

      }

      "return an error for a non-admin developer" in new Setup {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
          underTest.process(principalApp, cmdAsDev)
        }
      }

      "fail when we try to add too many redirect URIs" in new Setup {
        checkFailsWith("Can have at most 5 redirect URIs") {
          val brokenCmd = cmdAsAdmin.copy(newRedirectUris = (1 to 6).toList.map(id => s"http://somewhere.com/endpoint$id"))
          underTest.process(applicationData, brokenCmd)
        }
        ApplicationRepoMock.verifyZeroInteractions()
      }

      "fail when application is not standardAccess" in new Setup {
        checkFailsWith("App must have a STANDARD access type") {
          underTest.process(nonStandardAccessApp, cmdAsAdmin)
        }
        ApplicationRepoMock.verifyZeroInteractions()
      }
    }

    "given a subordinate application" should {
      "succeed for a developer" in new Setup {
        ApplicationRepoMock.UpdateRedirectUris.thenReturn(newRedirectUris)(subordinateApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(subordinateApp, cmdAsDev).value).value

        checkSuccessResult(developerActor)(result)
      }
    }
  }
}
