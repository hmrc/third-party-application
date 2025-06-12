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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.LoginRedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class AddLoginRedirectUrisCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new AddLoginRedirectUriCommandHandler(ApplicationRepoMock.aMock)

    val untouchedUri     = LoginRedirectUri.unsafeApply("https://leavemebe.example.com")
    val toAddRedirectUri = LoginRedirectUri.unsafeApply("https://new-url.example.com")
    val originalUris     = List(LoginRedirectUri.unsafeApply(untouchedUri.uri))
    val nonExistantUri   = LoginRedirectUri.unsafeApply("https://otherurl.com/not-there")

    val principalApp: StoredApplication = storedApp.withAccess(Access.Standard(originalUris))
    val subordinateApp                  = principalApp.inSandbox()
    val nonStandardAccessApp            = principalApp.withAccess(Access.Privileged())

    val developerActor = Actors.AppCollaborator(developerCollaborator.emailAddress)

    val timestamp  = FixedClock.instant
    val cmdAsAdmin = AddLoginRedirectUri(adminActor, toAddRedirectUri, instant)
    val cmdAsDev   = AddLoginRedirectUri(developerActor, toAddRedirectUri, instant)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.LoginRedirectUriAdded(_, appId, eventDateTime, actor, added) =>
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
        val fourUris        = (1 to 4).map(i => LoginRedirectUri.unsafeApply(s"https:/example$i.com")).toList
        val appWithFourUris = storedApp.withAccess(Access.Standard(fourUris))

        val expectedUrisAfterChange = fourUris :+ toAddRedirectUri
        ApplicationRepoMock.UpdateLoginRedirectUris.thenReturn(expectedUrisAfterChange)(appWithFourUris) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(appWithFourUris, cmdAsAdmin).value).value

        checkSuccessResult(adminActor)(result)
      }

      "return an error for a non-admin developer" in new Setup {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
          underTest.process(principalApp, cmdAsDev)
        }
      }

      "fail when we try to add a sixth URI" in new Setup {
        val fiveUris        = (1 to 5).map(i => LoginRedirectUri.unsafeApply(s"https:/example$i.com")).toList
        val appWithFiveUris = storedApp.withAccess(Access.Standard(fiveUris))
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
        ApplicationRepoMock.UpdateLoginRedirectUris.thenReturn(expectedUrisAfterChange)(subordinateApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(subordinateApp, cmdAsDev).value).value

        checkSuccessResult(developerActor)(result)
      }
    }
  }
}
