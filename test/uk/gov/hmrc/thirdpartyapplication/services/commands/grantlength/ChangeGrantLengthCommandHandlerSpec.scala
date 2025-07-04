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

package uk.gov.hmrc.thirdpartyapplication.services.commands.grantlength

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.GrantLength
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class ChangeGrantLengthCommandHandlerSpec extends CommandHandlerBaseSpec {

  val originalGrantLength = GrantLength.SIX_MONTHS
  val app                 = principalApp.copy(refreshTokensAvailableFor = originalGrantLength.period)

  trait Setup extends ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val gatekeeperUser         = "gkuser"
    val replaceWithGrantLength = GrantLength.ONE_YEAR
    val newApp                 = app.copy(refreshTokensAvailableFor = replaceWithGrantLength.period)

    val timestamp = FixedClock.instant
    val update    = ApplicationCommands.ChangeGrantLength(gatekeeperUser, instant, replaceWithGrantLength)

    val underTest = new ChangeGrantLengthCommandHandler(ApplicationRepoMock.aMock)

    def checkSuccessResult(expectedActor: Actors.GatekeeperUser)(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.GrantLengthChanged(_, appId, eventDateTime, anActor, anOldGrantLength, aNewGrantLength) =>
            appId shouldBe applicationId
            anActor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            anOldGrantLength shouldBe originalGrantLength.period.getDays
            aNewGrantLength shouldBe replaceWithGrantLength.period.getDays
        }
      }
    }
  }

  "process" should {
    "create correct events for a valid request with app" in new Setup {
      ApplicationRepoMock.UpdateGrantLength.thenReturnWhen(app.id, replaceWithGrantLength.period)(newApp)
      ApplicationRepoMock.RemoveOldGrantLength.thenReturnWhen(app.id)(newApp)

      checkSuccessResult(Actors.GatekeeperUser(gatekeeperUser)) {
        underTest.process(app, update)
      }
    }

    "return an error if the application already has refreshTokenAvailableFor 180 days" in new Setup {
      val updateWithSameGrantLength = update.copy(grantLength = GrantLength.SIX_MONTHS)

      checkFailsWith("Grant length is already 180 days") {
        underTest.process(app, updateWithSameGrantLength)
      }
    }

    "return an error if the application already has refreshTokenAvailableFor 0 days (i.e. 4 hours and no refresh tokens)" in new Setup {
      val updateWithSameGrantLength = update.copy(grantLength = GrantLength.FOUR_HOURS)

      checkFailsWith("Grant length is already 4 hours and no refresh tokens") {
        underTest.process(app.copy(refreshTokensAvailableFor = GrantLength.FOUR_HOURS.period), updateWithSameGrantLength)
      }
    }

  }
}
