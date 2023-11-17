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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.AddRedirectUri
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._

class AddRedirectUrisCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new AddRedirectUriCommandHandler(ApplicationRepoMock.aMock)

    val applicationId    = ApplicationId.random
    val untouchedUri     = RedirectUri.unsafeApply("https://leavemebe.example.com")
    val toAddRedirectUri = RedirectUri.unsafeApply("https://new-url.example.com")
    val originalUris     = List(RedirectUri.unsafeApply(untouchedUri.uri))
    val nonExistantUri   = RedirectUri.unsafeApply("https://otherurl.com/not-there")

    val principalApp: StoredApplication = anApplicationData(applicationId, access = Access.Standard(originalUris), collaborators = devAndAdminCollaborators)
    val subordinateApp                  = principalApp.copy(environment = Environment.SANDBOX.toString())
    val nonStandardAccessApp            = principalApp.copy(access = Access.Privileged())

    val developerActor = Actors.AppCollaborator(developerCollaborator.emailAddress)

    val timestamp  = FixedClock.instant
    val cmdAsAdmin = AddRedirectUri(adminActor, toAddRedirectUri, now)
    val cmdAsDev   = AddRedirectUri(developerActor, toAddRedirectUri, now)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.RedirectUriAdded(_, appId, eventDateTime, actor, added) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            added shouldBe toAddRedirectUri
        }
      }
    }
  }

  "AddRedirectUrisCommandHandler" when {
    "given a principal application" should {
      "succeed when application is standardAccess" in new Setup {
        val fourUris        = (1 to 4).map(i => RedirectUri.unsafeApply(s"https:/example$i.com")).toList
        val appWithFourUris = anApplicationData(applicationId, access = Access.Standard(fourUris), collaborators = devAndAdminCollaborators)

        val expectedUrisAfterChange = fourUris :+ toAddRedirectUri
        ApplicationRepoMock.UpdateRedirectUris.thenReturn(expectedUrisAfterChange)(appWithFourUris) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(appWithFourUris, cmdAsAdmin).value).value

        checkSuccessResult(adminActor)(result)
      }

      "return an error for a non-admin developer" in new Setup {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
          underTest.process(principalApp, cmdAsDev)
        }
      }

      "fail when we try to add a sixth URI" in new Setup {
        val fiveUris        = (1 to 5).map(i => RedirectUri.unsafeApply(s"https:/example$i.com")).toList
        val appWithFiveUris = anApplicationData(applicationId, access = Access.Standard(fiveUris), collaborators = devAndAdminCollaborators)
        checkFailsWith("Can have at most 5 redirect URIs") {
          val brokenCmd = cmdAsAdmin
          underTest.process(appWithFiveUris, brokenCmd)
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
        val expectedUrisAfterChange = List(untouchedUri, toAddRedirectUri)
        ApplicationRepoMock.UpdateRedirectUris.thenReturn(expectedUrisAfterChange)(subordinateApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(subordinateApp, cmdAsDev).value).value

        checkSuccessResult(developerActor)(result)
      }
    }
  }
}
