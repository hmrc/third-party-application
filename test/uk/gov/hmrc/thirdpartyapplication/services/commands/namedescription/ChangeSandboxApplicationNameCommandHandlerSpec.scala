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
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationName, ApplicationState, State, ValidatedApplicationName}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeSandboxApplicationName
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.UpliftNamingServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class ChangeSandboxApplicationNameCommandHandlerSpec extends CommandHandlerBaseSpec {

  val app = subordinateApp.withAccess(Access.Standard(importantSubmissionData = Some(testImportantSubmissionData)))

  trait Setup extends ApplicationRepositoryMockModule with UpliftNamingServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val oldName   = app.name
    val newName   = ApplicationName("New app name")
    val requester = "requester"

    val userId = adminOne.userId

    val newApp = app.copy(name = newName, normalisedName = newName.value.toLowerCase())

    val timestamp = FixedClock.instant
    val update    = ChangeSandboxApplicationName(developerActor, instant, ValidatedApplicationName(newName.value).get)

    val underTest = new ChangeSandboxApplicationNameCommandHandler(ApplicationRepoMock.aMock, UpliftNamingServiceMock.aMock)

    def checkSuccessResult(expectedActor: Actors.AppCollaborator)(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.SandboxApplicationNameChanged(_, appId, eventDateTime, actor, anOldName, aNewName) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            aNewName shouldBe newName
            anOldName shouldBe oldName
            anOldName should not be aNewName
        }
      }
    }
  }

  "process" should {
    "create correct events for a valid request with a standard app" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      ApplicationRepoMock.UpdateApplicationName.thenReturn(newApp)

      checkSuccessResult(developerActor) {
        underTest.process(app, update)
      }
    }

    "create correct events for a valid request with a priv app" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val priviledgedApp = app.withAccess(Access.Privileged())
      ApplicationRepoMock.UpdateApplicationName.thenReturn(priviledgedApp) // unmodified

      checkSuccessResult(developerActor) {
        underTest.process(priviledgedApp, update)
      }
    }

    "return an error if actor is not a collaborator on the application" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val instigatorNotOnApp = update.copy(actor = Actors.AppCollaborator(LaxEmailAddress("someEmail@some.com")))

      checkFailsWith(CommandFailures.ActorIsNotACollaboratorOnApp) {
        underTest.process(app, instigatorNotOnApp)
      }
    }

    "return an error if application is not in SANDBOX" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()

      checkFailsWith("App is not in Sandbox environment") {
        underTest.process(app.inProduction(), update)
      }
    }

    "return an error if application is still in the process of being approved" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val appPendingApproval = app.withState(ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, updatedOn = instant))

      checkFailsWith("App is not in PRE_PRODUCTION or in PRODUCTION state") {
        underTest.process(appPendingApproval, update)
      }
    }

    "return an error if the application already has the specified name" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val appAlreadyHasName = app.withName(newName)

      checkFailsWith("App already has that name") {
        underTest.process(appAlreadyHasName, update)
      }
    }

    "return an error if the name is a duplicate" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.failsWithDuplicateName()

      checkFailsWith("New name is a duplicate") {
        underTest.process(app, update)
      }
    }

    "return an error if the name is invalid" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.failsWithInvalidName()

      checkFailsWith("New name is invalid") {
        underTest.process(app, update)
      }
    }
  }
}
