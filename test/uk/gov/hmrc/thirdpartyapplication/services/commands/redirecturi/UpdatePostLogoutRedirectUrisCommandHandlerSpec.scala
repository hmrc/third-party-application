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

package uk.gov.hmrc.thirdpartyapplication.services.commands.redirecturi

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationIdData}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.PostLogoutRedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UpdatePostLogoutRedirectUris
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.PostLogoutRedirectUrisUpdated
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class UpdatePostLogoutRedirectUrisCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new UpdatePostLogoutRedirectUrisCommandHandler(ApplicationRepoMock.aMock)

    val applicationId                      = ApplicationIdData.one
    val applicationData: StoredApplication = storedApp
    val subordinateApp                     = applicationData.inSandbox()

    val nonStandardAccessApp = applicationData.withAccess(Access.Privileged())
    val developerActor       = Actors.AppCollaborator(developerOne.emailAddress)

    val oldRedirectUris           = List.empty
    val newPostLogoutRedirectUris = List("https://new-url.example.com/logout", "https://new-url.example.com/logout-redirect").map(PostLogoutRedirectUri.unsafeApply(_))

    val timestamp  = FixedClock.instant
    val cmdAsGK    = UpdatePostLogoutRedirectUris(gatekeeperActor, newPostLogoutRedirectUris, instant)
    val cmdAsAdmin = cmdAsGK.copy(actor = adminActor)
    val cmdAsDev   = cmdAsGK.copy(actor = developerActor)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case PostLogoutRedirectUrisUpdated(_, appId, eventDateTime, actor, oldUris, newUris) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            oldUris shouldBe oldRedirectUris
            newUris shouldBe newPostLogoutRedirectUris
        }
      }
    }
  }

  "UpdateRedirectUrisCommandHandler" when {
    "given a principal application" should {
      "succeed when application is standardAccess" in new Setup {
        ApplicationRepoMock.UpdatePostLogoutRedirectUris.thenReturn(newPostLogoutRedirectUris)(applicationData) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(applicationData, cmdAsAdmin).value).value

        checkSuccessResult(adminActor)(result)

      }

      "succeed when user is gatekeeper" in new Setup {
        ApplicationRepoMock.UpdatePostLogoutRedirectUris.thenReturn(newPostLogoutRedirectUris)(applicationData) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(applicationData, cmdAsGK).value).value
        inside(result) { case (_, events) =>
          events should have size 1
          val event = events.head

          inside(event) {
            case PostLogoutRedirectUrisUpdated(_, appId, eventDateTime, actor, oldUris, newUris) =>
              appId shouldBe applicationId
              actor shouldBe gatekeeperActor
              eventDateTime shouldBe timestamp
              oldUris shouldBe oldRedirectUris
              newUris shouldBe newPostLogoutRedirectUris
          }
        }
      }

      "return an error for a non-admin developer" in new Setup {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN or be a Gatekeeper User") {
          underTest.process(principalApp, cmdAsDev)
        }
      }

      "fail when we try to add too many redirect URIs" in new Setup {
        checkFailsWith("Can have at most 5 redirect URIs") {
          val brokenCmd = cmdAsAdmin.copy(newRedirectUris = (1 to 6).toList.map(id => PostLogoutRedirectUri.unsafeApply(s"https://somewhere.com/endpoint$id")))
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
        ApplicationRepoMock.UpdatePostLogoutRedirectUris.thenReturn(newPostLogoutRedirectUris)(subordinateApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(subordinateApp, cmdAsDev).value).value

        checkSuccessResult(developerActor)(result)
      }
    }
  }
}
