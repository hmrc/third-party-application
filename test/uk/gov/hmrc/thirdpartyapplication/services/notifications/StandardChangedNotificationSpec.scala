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

package uk.gov.hmrc.thirdpartyapplication.services.notifications

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.TermsAndConditionsLocations

class StandardChangedNotificationSpec extends AsyncHmrcSpec with ApplicationTestData {

  trait Setup extends EmailConnectorMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationId         = ApplicationId.random
    val devEmail              = "dev@example.com"
    val adminEmail            = "admin@example.com"
    val oldName               = "old app name"
    val newName               = "new app name"
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app            = anApplicationData(applicationId).copy(
      collaborators = Set(
        Collaborator(devEmail, Role.DEVELOPER, idOf(devEmail)),
        Collaborator(adminEmail, Role.ADMINISTRATOR, idOf(adminEmail))
      ),
      name = oldName,
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )
    val timestamp      = FixedClock.now
    val gatekeeperUser = "gkuser"
    val eventId        = UpdateApplicationEvent.Id.random
    val actor          = Actors.GatekeeperUser(gatekeeperUser)
  }

  "sendAdviceEmail" should {
    "successfully send email for PrivacyPolicyUrlChanged" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = PrivacyPolicyLocation.Url("https://example.com/old-privacy-policy")
      val newPrivacyPolicyUrl      = PrivacyPolicyLocation.Url("https://example.com/new-privacy-policy")

      val result = await(StandardChangedNotification.sendAdviceEmail(
        EmailConnectorMock.aMock,
        app,
        "admin@example.com",
        "privacy policy URL",
        previousPrivacyPolicyUrl.value,
        newPrivacyPolicyUrl.value
      ))
      result shouldBe HasSucceeded
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        adminEmail,
        app.name,
        "privacy policy URL",
        previousPrivacyPolicyUrl.value,
        newPrivacyPolicyUrl.value,
        Set(adminEmail, devEmail, responsibleIndividual.emailAddress.value)
      )
    }
  }
}
