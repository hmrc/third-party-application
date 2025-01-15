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

package uk.gov.hmrc.thirdpartyapplication.services.commands.delete

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.DeleteRestriction.{DoNotDelete, NoRestriction}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class AllowApplicationDeleteCommandHandlerSpec extends CommandHandlerBaseSpec {

  val reasons = "Some reasons"

  trait Setup extends ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appWithDeleteAllowed         = storedApp.inSandbox()
    val timestamp                    = FixedClock.instant
    val deleteRestrictionDoNotDelete = DoNotDelete(reasons, Actors.GatekeeperUser(gatekeeperUser), timestamp)
    val appWithDeleteBlocked         = appWithDeleteAllowed.copy(deleteRestriction = deleteRestrictionDoNotDelete)

    val underTest = new AllowApplicationDeleteCommandHandler(ApplicationRepoMock.aMock)

    def checkSuccessResult(expectedActor: Actors.GatekeeperUser)(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.AllowApplicationDelete(_, appId, eventDateTime, anActor, reason) =>
            appId shouldBe app.id
            anActor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            reason shouldBe reasons
        }
      }
    }
  }

  "AllowApplicationDelete" should {
    val cmd = ApplicationCommands.AllowApplicationDelete(gatekeeperUser, reasons, instant)

    "create correct event for a valid request with app" in new Setup {
      ApplicationRepoMock.UpdateDeleteRestriction.thenReturnWhen(NoRestriction)(appWithDeleteAllowed)

      checkSuccessResult(Actors.GatekeeperUser(gatekeeperUser)) {
        underTest.process(appWithDeleteBlocked, cmd)
      }
    }

    "return an error if delete is already allowed" in new Setup {

      checkFailsWith("Delete is already allowed") {
        underTest.process(appWithDeleteAllowed, cmd)
      }
    }

  }

}
