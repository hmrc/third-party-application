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

import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApiSubscribedV2
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.SubscriptionRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}

class SubscribeToApiCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with ApiIdentifierSyntax {

  trait Setup
      extends StrideGatekeeperRoleAuthorisationServiceMockModule
      with SubscriptionRepositoryMockModule {

    implicit val hc = HeaderCarrier()

    val underTest = new SubscribeToApiCommandHandler(SubscriptionRepoMock.aMock, StrideGatekeeperRoleAuthorisationServiceMock.aMock)

    val applicationId       = ApplicationId.random
    val gatekeeperUserActor = Actors.GatekeeperUser("Gatekeeper Admin")
    val apiIdentifier       = "some-context".asIdentifier("1.1")
    val timestamp           = FixedClock.instant

    val subscribeToApi = SubscribeToApi(gatekeeperUserActor, apiIdentifier, FixedClock.now)

    def checkSuccessResult(expectedActor: Actors.GatekeeperUser)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).right.value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApiSubscribedV2(_, appId, eventDateTime, actor, context, version) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            ApiIdentifier(context, version) shouldBe apiIdentifier
        }
      }
    }

    def checkFailsWith(msg: String)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1
      testThis.head shouldBe msg
    }
  }

  trait PrivilegedAndRopcSetup extends Setup {

    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: ApplicationId, testBlock: ApplicationData => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised
      testWithPrivilegedAndRopc(applicationId, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: ApplicationId, testBlock: ApplicationData => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      testWithPrivilegedAndRopc(applicationId, testBlock)
    }

    private def testWithPrivilegedAndRopc(applicationId: ApplicationId, testBlock: ApplicationData => Unit): Unit = {
      testBlock(anApplicationData(applicationId, access = Privileged(scopes = Set("scope1"))))
      testBlock(anApplicationData(applicationId, access = Ropc()))
    }
  }

  "process" should {
    "create an ApiSubscribed event when adding a subscription to a STANDARD application" in new Setup {
      SubscriptionRepoMock.IsSubscribed.isFalse()
      SubscriptionRepoMock.Add.succeeds()

      val app = anApplicationData(applicationId)

      checkSuccessResult(gatekeeperUserActor) {
        underTest.process(app, subscribeToApi)
      }
    }

    "fail to subscribe to an API already subscribed to" in new Setup {
      SubscriptionRepoMock.IsSubscribed.isTrue()

      val app = anApplicationData(applicationId)

      checkFailsWith("Application MyApp is already subscribed to API some-context v1.1") {
        underTest.process(app, subscribeToApi)
      }
    }
    "create an ApiSubscribed event when adding a subscription to a PRIVILEGED or ROPC application and the gatekeeper is logged in" in new PrivilegedAndRopcSetup {
      SubscriptionRepoMock.IsSubscribed.isFalse()
      SubscriptionRepoMock.Add.succeeds()

      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      testWithPrivilegedAndRopcGatekeeperLoggedIn(
        applicationId,
        { app =>
          checkSuccessResult(gatekeeperUserActor) {
            underTest.process(app, subscribeToApi)
          }
        }
      )
    }

    "return invalid with an error message when adding a subscription to a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in new PrivilegedAndRopcSetup {
      SubscriptionRepoMock.IsSubscribed.isFalse()

      testWithPrivilegedAndRopcGatekeeperNotLoggedIn(
        applicationId,
        { app =>
          checkFailsWith(s"Unauthorized to subscribe any API to app ${app.name}") {
            underTest.process(app, subscribeToApi)
          }
        }
      )
    }
  }
}
