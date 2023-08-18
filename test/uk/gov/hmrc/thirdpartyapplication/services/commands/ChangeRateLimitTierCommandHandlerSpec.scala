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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.RateLimitTier
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApiGatewayStoreMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

class ChangeRateLimitTierCommandHandlerSpec extends CommandHandlerBaseSpec {

  val originalRateLimitTier = RateLimitTier.BRONZE
  val app                   = principalApp.copy(rateLimitTier = Some(originalRateLimitTier))

  trait Setup extends ApiGatewayStoreMockModule with ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val gatekeeperUser           = "gkuser"
    val replaceWithRateLimitTier = RateLimitTier.RHODIUM
    val newApp                   = app.copy(rateLimitTier = Some(replaceWithRateLimitTier))

    val timestamp = FixedClock.instant
    val update    = ApplicationCommands.ChangeRateLimitTier(gatekeeperUser, now, replaceWithRateLimitTier)

    val underTest = new ChangeRateLimitTierCommandHandler(ApiGatewayStoreMock.aMock, ApplicationRepoMock.aMock)

    def checkSuccessResult(expectedActor: Actors.GatekeeperUser)(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.RateLimitChanged(_, appId, eventDateTime, anActor, oldRateLimit, newRateLimit) =>
            appId shouldBe applicationId
            anActor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            oldRateLimit shouldBe originalRateLimitTier
            newRateLimit shouldBe replaceWithRateLimitTier
        }
      }
    }
  }

  "process" should {
    "create correct events for a valid request with app" in new Setup {
      ApplicationRepoMock.UpdateApplicationRateLimit.thenReturn(app.id, replaceWithRateLimitTier)(newApp)
      ApiGatewayStoreMock.UpdateApplication.thenReturnHasSucceeded()

      checkSuccessResult(Actors.GatekeeperUser(gatekeeperUser)) {
        underTest.process(app, update)
      }
    }
  }
}
