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

import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import scala.concurrent.ExecutionContext.Implicits.global

import cats.data.NonEmptyChain
import cats.data.Validated.Invalid

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ChangeProductionApplicationPrivacyPolicyLocationCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData {

  trait Setup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationId         = ApplicationId.random
    val devEmail              = "dev@example.com"
    val adminEmail            = "admin@example.com"
    val oldUrl                = "http://example.com/old"
    val newUrl                = "http://example.com/new"
    val oldLocation           = PrivacyPolicyLocation.InDesktopSoftware
    val newLocation           = PrivacyPolicyLocation.Url(newUrl)
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocation.InDesktopSoftware,
      oldLocation,
      List.empty
    )

    val newJourneyApp = anApplicationData(applicationId).copy(
      collaborators = Set(
        Collaborator(devEmail, Role.DEVELOPER, idOf(devEmail)),
        Collaborator(adminEmail, Role.ADMINISTRATOR, idOf(adminEmail))
      ),
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val oldJourneyApp = anApplicationData(applicationId).copy(
      collaborators = Set(
        Collaborator(devEmail, Role.DEVELOPER, idOf(devEmail)),
        Collaborator(adminEmail, Role.ADMINISTRATOR, idOf(adminEmail))
      ),
      access = Standard(privacyPolicyUrl = Some(oldUrl))
    )

    val userId    = idsByEmail(adminEmail)
    val timestamp = FixedClock.now
    val update    = ChangeProductionApplicationPrivacyPolicyLocation(userId, timestamp, newLocation)
    val actor     = CollaboratorActor(adminEmail)

    val underTest = new ChangeProductionApplicationPrivacyPolicyLocationCommandHandler()
  }

  "process with a new journey app" should {
    "create correct events for a valid request" in new Setup {
      val result = await(underTest.process(newJourneyApp, update))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ProductionAppPrivacyPolicyLocationChanged]
      event.applicationId shouldBe applicationId
      event.actor shouldBe actor
      event.eventDateTime shouldBe timestamp
      event.oldLocation shouldBe oldLocation
      event.newLocation shouldBe newLocation
    }
    "return an error if instigator is not a collaborator on the application" in new Setup {
      val result = await(underTest.process(newJourneyApp, update.copy(instigator = UserId.random)))

      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }
    "return an error if instigator is not an admin on the application" in new Setup {
      val result = await(underTest.process(newJourneyApp, update.copy(instigator = idsByEmail(devEmail))))

      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }
    "return an error if application is still in the process of being approved" in new Setup {
      val result = await(underTest.process(newJourneyApp.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL)), update))

      result shouldBe Invalid(NonEmptyChain.one("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION"))
    }
    "return an error if application is non-standard" in new Setup {
      val result = await(underTest.process(newJourneyApp.copy(access = Privileged()), update))

      result shouldBe Invalid(NonEmptyChain.one("App must have a STANDARD access type"))
    }
  }

  "process with a legacy journey app" should {
    "create correct events for a valid request" in new Setup {
      val result = await(underTest.process(oldJourneyApp, update))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ProductionLegacyAppPrivacyPolicyLocationChanged]
      event.applicationId shouldBe applicationId
      event.actor shouldBe actor
      event.eventDateTime shouldBe timestamp
      event.oldUrl shouldBe oldUrl
      event.newUrl shouldBe newUrl
    }
    "return an error if instigator is not a collaborator on the application" in new Setup {
      val result = await(underTest.process(newJourneyApp, update.copy(instigator = UserId.random)))

      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }
    "return an error if instigator is not an admin on the application" in new Setup {
      val result = await(underTest.process(newJourneyApp, update.copy(instigator = idsByEmail(devEmail))))

      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }
    "return an error if application is still in the process of being approved" in new Setup {
      val result = await(underTest.process(newJourneyApp.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL)), update))

      result shouldBe Invalid(NonEmptyChain.one("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION"))
    }
    "return an error if application is non-standard" in new Setup {
      val result = await(underTest.process(newJourneyApp.copy(access = Privileged()), update))

      result shouldBe Invalid(NonEmptyChain.one("App must have a STANDARD access type"))
    }
  }
}
