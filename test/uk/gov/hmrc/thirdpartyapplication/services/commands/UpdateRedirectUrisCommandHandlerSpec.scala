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
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

class UpdateRedirectUrisCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest = new UpdateRedirectUrisCommandHandler(ApplicationRepoMock.aMock)

    val applicationId                    = ApplicationId.random
    val applicationData: ApplicationData = anApplicationData(applicationId)

    val nonStandardAccessApp = applicationData.copy(access = Privileged())
    val developer            = applicationData.collaborators.head
    val developerActor       = Actors.AppCollaborator(developer.emailAddress)

    val oldRedirectUris = List.empty
    val newRedirectUris = List("https://new-url.example.com", "https://new-url.example.com/other-redirect")

    val timestamp = FixedClock.instant
    val cmd       = UpdateRedirectUris(developerActor, oldRedirectUris, newRedirectUris, FixedClock.now)

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

  "update with UpdateRedirectUris" should {

    "succeed when application is standardAccess" in new Setup {
      ApplicationRepoMock.UpdateRedirectUris.thenReturn(applicationId, newRedirectUris)(applicationData) // Dont need to test the repo here so just return any app

      val result = await(underTest.process(applicationData, cmd).value).right.value

      checkSuccessResult(developerActor)(result)

    }

    "fail when application is not standardAccess" in new Setup {
      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(nonStandardAccessApp, cmd)
      }
      ApplicationRepoMock.verifyZeroInteractions()
    }
  }

}
