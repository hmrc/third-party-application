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

package uk.gov.hmrc.thirdpartyapplication.services.commands.ipallowlist

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actor
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{CidrBlock, IpAllowlist}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class ChangeIpAllowlistCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val anApplication = storedApp

    val oldIpAllowList = List(
      CidrBlock("1.0.0.0/24")
    )

    val newIpAllowlist = List(
      CidrBlock("1.0.0.0/24"),
      CidrBlock("2.0.0.0/24")
    )

    val timestamp = FixedClock.instant
    val update    = ApplicationCommands.ChangeIpAllowlist(loggedInAsActor, instant, true, oldIpAllowList, newIpAllowlist)

    val underTest = new ChangeIpAllowlistCommandHandler(ApplicationRepoMock.aMock)

    def checkSuccessResult(expectedActor: Actor)(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.IpAllowlistCidrBlockChanged(_, appId, eventDateTime, anActor, required, oldIps, newIps) =>
            appId shouldBe applicationId
            anActor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            required shouldBe true
            oldIps shouldBe oldIpAllowList
            newIps shouldBe newIpAllowlist
        }
      }
    }
  }

  "process" should {
    "create correct events for a valid request with app for an admin on the app" in new Setup {
      val ipAllowList = IpAllowlist(true, newIpAllowlist.map(_.ipAddress).toSet)

      ApplicationRepoMock.UpdateIpAllowlist.thenReturnWhen(applicationId, ipAllowList)(anApplication)

      checkSuccessResult(loggedInAsActor) {
        underTest.process(anApplication, update)
      }
    }

    "create correct events for a valid request with app for a gatekeeper user" in new Setup {
      val ipAllowList = IpAllowlist(true, newIpAllowlist.map(_.ipAddress).toSet)

      ApplicationRepoMock.UpdateIpAllowlist.thenReturnWhen(applicationId, ipAllowList)(anApplication)

      checkSuccessResult(gatekeeperActor) {
        underTest.process(anApplication, update.copy(actor = gatekeeperActor))
      }
    }

    "fail due to invalid IP CIDR blocks" in new Setup {
      val badIpAllowList = List(
        CidrBlock("Bad CIDR Block")
      )
      val badUpdate      = ApplicationCommands.ChangeIpAllowlist(loggedInAsActor, instant, true, oldIpAllowList, badIpAllowList)

      checkFailsWith("Not all new allowlist IP addresses are valid CIDR blocks") {
        underTest.process(anApplication, badUpdate)
      }

      ApplicationRepoMock.verifyZeroInteractions()
    }

    "fail due to invalid user" in new Setup {
      val badUpdate = update.copy(actor = developerAsActor)

      checkFailsWith("App is in PRODUCTION so User must be an ADMIN or be a Gatekeeper User") {
        underTest.process(anApplication, badUpdate)
      }

      ApplicationRepoMock.verifyZeroInteractions()
    }
  }
}
