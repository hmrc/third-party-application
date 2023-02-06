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
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule

class ChangeProductionApplicationPrivacyPolicyLocationCommandHandlerSpec
    extends AsyncHmrcSpec
    with ApplicationTestData
    with CommandActorExamples
    with CommandCollaboratorExamples
    with CommandApplicationExamples {

  trait Setup extends ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val oldUrl      = "http://example.com/old"
    val newUrl      = "http://example.com/new"
    val newLocation = PrivacyPolicyLocation.Url(newUrl)

    val newJourneyApp = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator
      ),
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val oldJourneyApp = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator
      ),
      access = Standard(privacyPolicyUrl = Some(oldUrl))
    )

    val userId    = idsByEmail(adminEmail)
    val timestamp = FixedClock.now
    val actor     = CollaboratorActor(adminEmail)

    val update = ChangeProductionApplicationPrivacyPolicyLocation(userId, timestamp, newLocation)

    val underTest = new ChangeProductionApplicationPrivacyPolicyLocationCommandHandler(ApplicationRepoMock.aMock)

    def checkSuccessResult(expectedActor: Actor)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).right.value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ProductionAppPrivacyPolicyLocationChanged(_, appId, eventDateTime, actor, oldLocation, eNewLocation) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            oldLocation shouldBe privicyPolicyLocation
            eNewLocation shouldBe newLocation
        }
      }
    }

    def checkLegacySuccessResult(expectedActor: Actor)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).right.value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ProductionLegacyAppPrivacyPolicyLocationChanged(_, appId, eventDateTime, actor, eOldUrl, eNewUrl) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            eOldUrl shouldBe oldUrl
            eNewUrl shouldBe newUrl
        }
      }
    }

    def checkFailsWith(msg: String)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1
      testThis.head shouldBe msg
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
        underTest.process(newJourneyApp, update.copy(instigator = idsByEmail(devEmail)))
      }
    }

    "return an error if application is still in the process of being approved" in new Setup {
      checkFailsWith("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION") {
        underTest.process(newJourneyApp.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL)), update)
      }
    }

    "return an error if application is non-standard" in new Setup {
      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(newJourneyApp.copy(access = Privileged()), update)
      }
    }
  }

  "process with a legacy journey app" should {
    "create correct events for a valid request" in new Setup {
      ApplicationRepoMock.UpdateLegacyPrivacyPolicyLocation.succeeds()

      checkLegacySuccessResult(actor)(underTest.process(oldJourneyApp, update))
    }

    "return an error if instigator is not a collaborator on the application" in new Setup {
      checkFailsWith("User must be an ADMIN") {
        underTest.process(oldJourneyApp, update.copy(instigator = UserId.random))
      }
    }

    "return an error if instigator is not an admin on the application" in new Setup {
      checkFailsWith("User must be an ADMIN") {
        underTest.process(oldJourneyApp, update.copy(instigator = idsByEmail(devEmail)))
      }
    }

    "return an error if application is still in the process of being approved" in new Setup {
      checkFailsWith("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION") {
        underTest.process(oldJourneyApp.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL)), update)
      }
    }

    "return an error if application is non-standard" in new Setup {
      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(oldJourneyApp.copy(access = Privileged()), update)
      }
    }
  }
}
