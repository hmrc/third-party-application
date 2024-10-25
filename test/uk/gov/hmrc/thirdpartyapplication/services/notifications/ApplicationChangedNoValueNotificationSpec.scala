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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.EventId
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ApplicationChangedNoValueNotificationSpec extends AsyncHmrcSpec with ApplicationTestData {

  trait Setup extends EmailConnectorMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationId         = ApplicationId.random
    val devEmail              = developerOne.emailAddress
    val anAdminEmail          = adminOne.emailAddress
    val oldName               = ApplicationName("old app name")
    val newName               = ApplicationName("new app name")
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app            = anApplicationData().copy(
      name = oldName,
      access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )
    val timestamp      = now
    val gatekeeperUser = "gkuser"
    val eventId        = EventId.random
    val actor          = Actors.GatekeeperUser(gatekeeperUser)
  }

  "sendAdviceEmail" should {
    "successfully send email for PrivacyPolicyUrlChanged" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.thenReturnSuccess()

      val result = await(ApplicationChangedNoValueNotification.sendAdviceEmail(
        EmailConnectorMock.aMock,
        app,
        adminOne.emailAddress.text,
        "privacy policy URL"
      ))
      result shouldBe HasSucceeded
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.verifyCalledWith(
        adminOne.emailAddress.text,
        app.name,
        "privacy policy URL",
        app.collaborators.map(_.emailAddress) + responsibleIndividual.emailAddress
      )
    }
  }
}
