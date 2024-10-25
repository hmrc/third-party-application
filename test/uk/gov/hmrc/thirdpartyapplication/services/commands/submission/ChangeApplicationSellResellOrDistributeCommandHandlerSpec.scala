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

package uk.gov.hmrc.thirdpartyapplication.services.commands.submission

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, SellResellOrDistribute}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeApplicationSellResellOrDistribute
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ApplicationSellResellOrDistributeChanged
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class ChangeApplicationSellResellOrDistributeCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appAdminEmail             = "admin@example.com".toLaxEmail
    val oldSellResellOrDistribute = Some(SellResellOrDistribute("No"))
    val stdAccess                 = Access.Standard(sellResellOrDistribute = oldSellResellOrDistribute)
    val standardApp               = anApplicationData().copy(access = stdAccess)
    val privApp                   = anApplicationData().copy(access = Access.Privileged())

    val ts = FixedClock.instant

    val underTest = new ChangeApplicationSellResellOrDistributeCommandHandler(
      ApplicationRepoMock.aMock,
      FixedClock.clock
    )
  }

  "process" should {
    "create correct event for a valid request" in new Setup {

      ApplicationRepoMock.Save.thenAnswer()

      val newSellResellOrDistribute = SellResellOrDistribute("Yes")
      val result                    = await(underTest.process(standardApp, ChangeApplicationSellResellOrDistribute(Actors.AppCollaborator(appAdminEmail), ts, newSellResellOrDistribute)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: ApplicationSellResellOrDistributeChanged =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.newSellResellOrDistribute shouldBe newSellResellOrDistribute
            event.oldSellResellOrDistribute shouldBe oldSellResellOrDistribute
        }
      }

      ApplicationRepoMock.Save.verifyCalled()
    }

    "return an error if the application is not standard access" in new Setup {

      val newSellResellOrDistribute = SellResellOrDistribute("Yes")
      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(privApp, ChangeApplicationSellResellOrDistribute(Actors.AppCollaborator(appAdminEmail), ts, newSellResellOrDistribute))
      }
    }
  }
}
