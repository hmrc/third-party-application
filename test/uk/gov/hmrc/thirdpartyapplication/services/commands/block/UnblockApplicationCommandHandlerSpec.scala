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

package uk.gov.hmrc.thirdpartyapplication.services.commands.block

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UnblockApplication
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ApplicationUnblocked
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class UnblockApplicationCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val app = storedApp.inSandbox()
    val ts  = FixedClock.instant

    val underTest = new UnblockApplicationCommandHandler(
      ApplicationRepoMock.aMock,
      FixedClock.clock
    )
  }

  "UnblockApplication" should {

    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.Save.thenReturn(app)

      val cmd = UnblockApplication(gatekeeperUser, ts)

      val result = await(underTest.process(app, cmd).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: ApplicationUnblocked =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gatekeeperUser)
        }
      }
    }
  }
}
