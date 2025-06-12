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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.LoginRedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeleteLoginRedirectUri
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class DeleteLoginRedirectUrisCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new DeleteLoginRedirectUriCommandHandler(ApplicationRepoMock.aMock)

    val toBeDeletedRedirectUri          = LoginRedirectUri.unsafeApply("https://new-url.example.com")
    val toRemainRedirectUri             = LoginRedirectUri.unsafeApply("https://new-url.example.com/other-redirect")
    val originalUris                    = List(toBeDeletedRedirectUri, toRemainRedirectUri)
    val nonExistantUri                  = LoginRedirectUri.unsafeApply("https://otherurl.com/not-there")
    val principalApp: StoredApplication = storedApp.withAccess(Access.Standard(originalUris)).withCollaborators(devAndAdminCollaborators)
    val subordinateApp                  = principalApp.inSandbox()

    val nonStandardAccessApp = principalApp.withAccess(Access.Privileged())
    val developerActor       = Actors.AppCollaborator(developerCollaborator.emailAddress)

    val timestamp  = FixedClock.instant
    val cmdAsAdmin = DeleteLoginRedirectUri(adminActor, toBeDeletedRedirectUri, instant)
    val cmdAsDev   = DeleteLoginRedirectUri(developerActor, toBeDeletedRedirectUri, instant)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(result: CommandHandler.Success) = {
      inside(result) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.LoginRedirectUriDeleted(_, appId, eventDateTime, actor, deleted) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            deleted shouldBe toBeDeletedRedirectUri
        }
      }
    }
  }

  "DeleteRedirectUrisCommandHandler" when {
    "given a principal application" should {
      "succeed when application is standardAccess" in new Setup {
        ApplicationRepoMock.UpdateLoginRedirectUris.thenReturn(List(toRemainRedirectUri))(principalApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(principalApp, cmdAsAdmin).value).value

        checkSuccessResult(adminActor)(result)

      }

      "return an error for a non-admin developer" in new Setup {
        checkFailsWith("App is in PRODUCTION so User must be an ADMIN") {
          underTest.process(principalApp, cmdAsDev)
        }
      }

      "fail when we try to delete non existant URI" in new Setup {
        checkFailsWith(s"RedirectUri ${nonExistantUri} does not exist") {
          val brokenCmd = cmdAsAdmin.copy(redirectUriToDelete = nonExistantUri)
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
        ApplicationRepoMock.UpdateLoginRedirectUris.thenReturn(List(toRemainRedirectUri))(subordinateApp) // Dont need to test the repo here so just return any app

        val result = await(underTest.process(subordinateApp, cmdAsDev).value).value

        checkSuccessResult(developerActor)(result)
      }
    }
  }
}
