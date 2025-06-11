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

package uk.gov.hmrc.thirdpartyapplication.services.commands.subscription

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApiIdentifier}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UnsubscribeFromRetiredApi
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ApiUnsubscribedV2
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.SubscriptionRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class UnsubscribeFromRetiredApiCommandHandlerSpec extends CommandHandlerBaseSpec with ApiIdentifierSyntax {

  trait Setup extends SubscriptionRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val underTest = new UnsubscribeFromRetiredApiCommandHandler(SubscriptionRepoMock.aMock)

    val processActor  = Actors.Process("Api Publisher")
    val apiIdentifier = "some-context".asIdentifier("1.1")
    val timestamp     = FixedClock.instant

    val unsubscribeFromRetiredApi = UnsubscribeFromRetiredApi(processActor, apiIdentifier, instant)

    def checkSuccessResult(expectedActor: Actors.Process)(fn: => CommandHandler.AppCmdResultT) = {
      val testThis = await(fn.value).value

      inside(testThis) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApiUnsubscribedV2(_, appId, eventDateTime, actor, context, version) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            ApiIdentifier(context, version) shouldBe apiIdentifier
        }
      }
    }
  }

  "process" should {
    "create an ApiUnsubscribed event when removing a subscription from an application" in new Setup {
      SubscriptionRepoMock.IsSubscribed.isTrue()
      SubscriptionRepoMock.Remove.succeeds()

      val app = storedApp

      checkSuccessResult(processActor) {
        underTest.process(app, unsubscribeFromRetiredApi)
      }
    }
  }
}
