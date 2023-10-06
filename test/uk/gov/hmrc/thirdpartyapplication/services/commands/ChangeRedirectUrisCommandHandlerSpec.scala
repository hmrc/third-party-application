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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.RedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeRedirectUri
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, _}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._

class ChangeRedirectUrisCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new ChangeRedirectUriCommandHandler(ApplicationRepoMock.aMock)

    val applicationId                 = ApplicationId.random
    val untouchedUri                  = RedirectUri.unsafeApply("https://leavemebe.example.com")
    val toBeReplacedRedirectUri       = RedirectUri.unsafeApply("https://new-url.example.com")
    val replacementUri                = RedirectUri.unsafeApply("https://new-url.example.com/other-redirect")
    val originalUris                  = List(toBeReplacedRedirectUri.uri, untouchedUri.uri)
    val nonExistantUri                = RedirectUri.unsafeApply("https://otherurl.com/not-there")
    val principalApp: ApplicationData = anApplicationData(applicationId, access = Standard(originalUris), collaborators = devAndAdminCollaborators)
    val subordinateApp                = principalApp.copy(environment = Environment.SANDBOX.toString())

    val nonStandardAccessApp = principalApp.copy(access = Privileged())
    val developerActor       = Actors.AppCollaborator(developerCollaborator.emailAddress)

    val timestamp  = FixedClock.instant
    val cmdAsAdmin = ChangeRedirectUri(adminActor, toBeReplacedRedirectUri, replacementUri, now)
    val cmdAsDev   = ChangeRedirectUri(developerActor, toBeReplacedRedirectUri, replacementUri, now)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.RedirectUriChanged(_, appId, eventDateTime, actor, oldUri, newUri) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            oldUri shouldBe toBeReplacedRedirectUri
            newUri shouldBe replacementUri
        }
      }
    }
  }

  "ChangeRedirectUrisCommandHandler" when {
    "given a principal application" should {
      "succeed when application is standardAccess" in new Setup {
        val expectedUrisAfterChange = List(replacementUri.uri, untouchedUri.uri)
        ApplicationRepoMock.UpdateRedirectUris.thenReturn(expectedUrisAfterChange)(principalApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(principalApp, cmdAsAdmin).value).value

        checkSuccessResult(adminActor)(result)
      }

      "return an error for a non-admin developer" in new Setup {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
          underTest.process(principalApp, cmdAsDev)
        }
      }

      "fail when we try to change non existant URI" in new Setup {
        checkFailsWith(s"RedirectUri ${nonExistantUri.uri} does not exist") {
          val brokenCmd = cmdAsAdmin.copy(redirectUriToReplace = nonExistantUri)
          underTest.process(principalApp, brokenCmd)
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
        val expectedUrisAfterChange = List(replacementUri.uri, untouchedUri.uri)
        ApplicationRepoMock.UpdateRedirectUris.thenReturn(expectedUrisAfterChange)(subordinateApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(subordinateApp, cmdAsDev).value).value

        checkSuccessResult(developerActor)(result)
      }
    }
  }
}
