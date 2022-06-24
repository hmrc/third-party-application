/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.data.{NonEmptyChain, NonEmptyList}
import cats.data.Validated.{Invalid, Valid}
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.UpliftNamingServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import java.time.LocalDateTime

class ChangeProductionApplicationNameCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData {

  trait Setup extends UpliftNamingServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationId         = ApplicationId.random
    val devEmail              = "dev@example.com"
    val adminEmail            = "admin@example.com"
    val oldName               = "old app name"
    val newName               = "new app name"
    val gatekeeperUser        = "gkuser"
    val requester             = "requester"
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app                  = anApplicationData(applicationId).copy(
      collaborators = Set(
        Collaborator(devEmail, Role.DEVELOPER, idOf(devEmail)),
        Collaborator(adminEmail, Role.ADMINISTRATOR, idOf(adminEmail))
      ),
      name = oldName,
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )
    val userId               = idsByEmail(adminEmail)
    val timestamp            = LocalDateTime.now
    val update               = ChangeProductionApplicationName(userId, timestamp, gatekeeperUser, newName)
    val nameChangedEvent     = NameChanged(applicationId, timestamp, userId, oldName, newName)
    val nameChangeEmailEvent = NameChangedEmailSent(applicationId, timestamp, userId, oldName, newName, adminEmail)
    val nameChangedAuditEvent = NameChangedAudit(applicationId, timestamp, userId, oldName, newName, adminEmail, gatekeeperUser)

    val underTest = new ChangeProductionApplicationNameCommandHandler(UpliftNamingServiceMock.aMock)
  }
  "process" should {
    "create correct events for a valid request" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val result = await(underTest.process(app, update))

      result shouldBe Valid(NonEmptyList.of(nameChangedEvent, nameChangeEmailEvent, nameChangedAuditEvent))
    }
    "return an error if instigator is not a collaborator on the application" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val result = await(underTest.process(app, update.copy(instigator = UserId.random)))

      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }
    "return an error if instigator is not an admin on the application" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val result = await(underTest.process(app, update.copy(instigator = idsByEmail(devEmail))))

      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }
    "return an error if application is still in the process of being approved" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val result = await(underTest.process(app.copy(state = ApplicationState(State.PENDING_GATEKEEPER_APPROVAL)), update))

      result shouldBe Invalid(NonEmptyChain.one("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION"))
    }
    "return an error if application is non-standard" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val result = await(underTest.process(app.copy(access = Privileged()), update))

      result shouldBe Invalid(NonEmptyChain.one("App must have a STANDARD access type"))
    }
    "return an error if the application already has the specified name" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.succeeds()
      val result = await(underTest.process(app.copy(name = newName), update))
      result shouldBe Invalid(NonEmptyChain.one("App already has that name"))
    }
    "return an error if the name is a duplicate" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.failsWithDuplicateName()
      val result = await(underTest.process(app, update))
      result shouldBe Invalid(NonEmptyChain.one("New name is a duplicate"))
    }
    "return an error if the name is invalid" in new Setup {
      UpliftNamingServiceMock.ValidateApplicationName.failsWithInvalidName()
      val result = await(underTest.process(app, update))
      result shouldBe Invalid(NonEmptyChain.one("New name is invalid"))
    }
  }
}
