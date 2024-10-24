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

package uk.gov.hmrc.thirdpartyapplication.services.commands.policy

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationState, State}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.PrivacyPolicyLocations
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeProductionApplicationPrivacyPolicyLocation
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class ChangeProductionApplicationPrivacyPolicyLocationCommandHandlerSpec extends CommandHandlerBaseSpec with FixedClock {

  trait Setup extends ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val oldUrl      = "http://example.com/old"
    val newUrl      = "http://example.com/new"
    val newLocation = PrivacyPolicyLocations.Url(newUrl)

    val newJourneyApp = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        otherAdminCollaborator
      ),
      access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val oldJourneyApp = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        otherAdminCollaborator
      ),
      access = Access.Standard(privacyPolicyUrl = Some(oldUrl))
    )

    val userId = otherAdminCollaborator.userId
    val actor  = otherAdminAsActor

    val update = ChangeProductionApplicationPrivacyPolicyLocation(userId, instant, newLocation)

    val underTest = new ChangeProductionApplicationPrivacyPolicyLocationCommandHandler(ApplicationRepoMock.aMock)

    def checkSuccessResult(expectedActor: Actor)(fn: => CommandHandler.AppCmdResultT) = {
      val testThis = await(fn.value).value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.ProductionAppPrivacyPolicyLocationChanged(_, appId, eventDateTime, actor, oldLocation, eNewLocation) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe instant
            oldLocation shouldBe privicyPolicyLocation
            eNewLocation shouldBe newLocation
        }
      }
    }

    def checkLegacySuccessResult(expectedActor: Actor)(fn: => CommandHandler.AppCmdResultT) = {
      val testThis = await(fn.value).value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.ProductionLegacyAppPrivacyPolicyLocationChanged(_, appId, eventDateTime, actor, eOldUrl, eNewUrl) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe instant
            eOldUrl shouldBe oldUrl
            eNewUrl shouldBe newUrl
        }
      }
    }
  }

  "process with a new journey app" should {

    "create correct events for a valid request" in new Setup {
      ApplicationRepoMock.UpdatePrivacyPolicyLocation.succeeds()

      checkSuccessResult(actor)(underTest.process(newJourneyApp, update))
    }

    "return an error if instigator is not a collaborator on the application" in new Setup {
      checkFailsWith("User must be an ADMIN") {
        underTest.process(newJourneyApp, update.copy(instigator = UserId.random))
      }
    }

    "return an error if instigator is not an admin on the application" in new Setup {
      checkFailsWith("User must be an ADMIN") {
        underTest.process(newJourneyApp, update.copy(instigator = developerOne.userId))
      }
    }

    "return an error if application is still in the process of being approved" in new Setup {
      checkFailsWith("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION") {
        underTest.process(newJourneyApp.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, updatedOn = instant)), update)
      }
    }

    "return an error if application is non-standard" in new Setup {
      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(newJourneyApp.copy(access = Access.Privileged()), update)
      }
    }
  }

  "process with a legacy journey app" should {
    "create correct events for a valid request" in new Setup {
      ApplicationRepoMock.UpdateLegacyPrivacyPolicyUrl.succeeds()

      checkLegacySuccessResult(actor)(underTest.process(oldJourneyApp, update))
    }

    "return an error if instigator is not a collaborator on the application" in new Setup {
      checkFailsWith("User must be an ADMIN") {
        underTest.process(oldJourneyApp, update.copy(instigator = UserId.random))
      }
    }

    "return an error if instigator is not an admin on the application" in new Setup {
      checkFailsWith("User must be an ADMIN") {
        underTest.process(oldJourneyApp, update.copy(instigator = developerOne.userId))
      }
    }

    "return an error if application is still in the process of being approved" in new Setup {
      checkFailsWith("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION") {
        underTest.process(oldJourneyApp.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, updatedOn = instant)), update)
      }
    }

    "return an error if application is non-standard" in new Setup {
      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(oldJourneyApp.copy(access = Access.Privileged()), update)
      }
    }
  }
}
