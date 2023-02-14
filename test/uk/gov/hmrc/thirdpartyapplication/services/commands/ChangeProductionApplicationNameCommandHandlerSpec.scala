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

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.UpliftNamingServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors

class ChangeProductionApplicationNameCommandHandlerSpec
    extends AsyncHmrcSpec
    with ApplicationTestData
    with CommandActorExamples
    with CommandCollaboratorExamples
    with CommandApplicationExamples {

  val app = principalApp.copy(access = Standard(importantSubmissionData = Some(testImportantSubmissionData)))

  trait Setup extends ApplicationRepositoryMockModule with UpliftNamingServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val oldName        = "old app name"
    val newName        = "new app name"
    val gatekeeperUser = "gkuser"
    val requester      = "requester"

    val userId = idOf(adminEmail)

    val timestamp = FixedClock.now
    val update    = ChangeProductionApplicationName(userId, timestamp, gatekeeperUser, newName)

    val underTest = new ChangeProductionApplicationNameCommandHandler(ApplicationRepoMock.aMock, UpliftNamingServiceMock.aMock)

    def checkSuccessResult(expectedActor: Actors.GatekeeperUser)(fn: => CommandHandler.ResultT) = {
      val testMe = await(fn.value).right.value

      inside(testMe) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ProductionAppNameChanged(_, appId, eventDateTime, actor, oldName, eNewName, requestingAdminEmail) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            oldName shouldBe app.name
            eNewName shouldBe newName
            requestingAdminEmail shouldBe adminEmail
        }
      }
    }

    def checkFailsWith(msg: String)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1
      testThis.head shouldBe msg
    }

  }

  "process" should {
    "create correct events for a valid request with a standard app" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      ApplicationRepoMock.UpdateApplicationName.thenReturn(app) // unmodified

      checkSuccessResult(Actors.GatekeeperUser(gatekeeperUser)) {
        underTest.process(app, update)
      }
    }

    "create correct events for a valid request with a priv app" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val priviledgedApp = app.copy(access = Privileged())
      ApplicationRepoMock.UpdateApplicationName.thenReturn(priviledgedApp) // unmodified

      checkSuccessResult(Actors.GatekeeperUser(gatekeeperUser)) {
        underTest.process(priviledgedApp, update)
      }
    }

    "return an error if instigator is not a collaborator on the application" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val instigatorNotOnApp = update.copy(instigator = UserId.random)

      checkFailsWith("User must be an ADMIN") {
        underTest.process(app, instigatorNotOnApp)
      }
    }

    "return an error if instigator is not an admin on the application" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val instigatorIsDev = update.copy(instigator = idOf(devEmail))

      checkFailsWith("User must be an ADMIN") {
        underTest.process(app, instigatorIsDev)
      }
    }

    "return an error if application is still in the process of being approved" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val appPendingApproval = app.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL))

      checkFailsWith("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION") {
        underTest.process(appPendingApproval, update)
      }
    }

    "return an error if the application already has the specified name" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val appAlreadyHasName = app.copy(name = newName)

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
