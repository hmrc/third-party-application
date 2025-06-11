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

package uk.gov.hmrc.thirdpartyapplication.services.commands.namedescription

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationState, State}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommands, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class ChangeSandboxApplicationDescriptionCommandHandlerSpec extends CommandHandlerBaseSpec {

  val app = subordinateApp.withAccess(Access.Standard(importantSubmissionData = Some(testImportantSubmissionData)))

  trait Setup extends ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val oldDescription = app.description
    val newDescription = "New app description"
    val requester      = "requester"

    val userId = adminOne.userId

    val timestamp = FixedClock.instant
    val update    = ApplicationCommands.ChangeSandboxApplicationDescription(developerActor, instant, newDescription)

    val underTest = new ChangeSandboxApplicationDescriptionCommandHandler(ApplicationRepoMock.aMock)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.SandboxApplicationDescriptionChanged(_, appId, eventDateTime, actor, anOldDescription, aNewDescription) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            aNewDescription shouldBe newDescription
            anOldDescription shouldBe oldDescription
            anOldDescription should not be aNewDescription
        }
      }
    }
  }

  "process" should {
    "create correct events for a valid request with a standard app" in new Setup {
      ApplicationRepoMock.UpdateDescription.succeedsFor(Some(newDescription))

      checkSuccessResult(developerActor) {
        underTest.process(app, update)
      }
    }

    "create correct events for a valid request with a priv app" in new Setup {
      val priviledgedApp = app.withAccess(Access.Privileged())
      ApplicationRepoMock.UpdateDescription.succeedsFor(Some(newDescription)) // unmodified

      checkSuccessResult(developerActor) {
        underTest.process(priviledgedApp, update)
      }
    }

    "return an error if actor is not a collaborator on the application" in new Setup {
      val instigatorNotOnApp = update.copy(actor = Actors.AppCollaborator(LaxEmailAddress("someEmail@some.com")))

      checkFailsWith(CommandFailures.ActorIsNotACollaboratorOnApp) {
        underTest.process(app, instigatorNotOnApp)
      }
    }

    "return an error if application is not in SANDBOX" in new Setup {
      checkFailsWith("App is not in Sandbox environment") {
        underTest.process(app.inProduction(), update)
      }
    }

    "return an error if application is still in the process of being approved" in new Setup {
      val appPendingApproval = app.withState(ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, updatedOn = instant))

      checkFailsWith("App is not in PRE_PRODUCTION or in PRODUCTION state") {
        underTest.process(appPendingApproval, update)
      }
    }

    "return an error if the application has no existing description" in new Setup {
      val appAlreadyHasDescription = app.copy(description = Some(newDescription))

      checkFailsWith("App already has that description") {
        underTest.process(appAlreadyHasDescription, update)
      }
    }
  }
}
