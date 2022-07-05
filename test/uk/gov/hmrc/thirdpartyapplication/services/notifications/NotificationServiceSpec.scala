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

package uk.gov.hmrc.thirdpartyapplication.services

import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class NotificationServiceSpec
  extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with ApplicationTestData
    with UpliftRequestSamples
    with FixedClock {

  trait Setup extends EmailConnectorMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationId = ApplicationId.random
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")
    val testImportantSubmissionData = ImportantSubmissionData(Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty)
    val applicationData: ApplicationData = anApplicationData(
      applicationId,
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData)))

    val adminEmail = "admin@example.com"
    val gatekeeperUser = "gkuser"
    val oldAppName = "old name"
    val newAppName = "new name"
    val underTest = new NotificationService(EmailConnectorMock.aMock)
  }

  "sendNotifications" should {
    "when receive a ProductionAppNameChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationName.thenReturnSuccess()
      val event = ProductionAppNameChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), oldAppName, newAppName, adminEmail)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationName.verifyCalledWith(adminEmail, oldAppName, newAppName, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }

    "when receive a TermsAndConditionsUrlChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousTermsAndConditionsUrl = "https://example.com/old-terms-and-conditions"
      val newTermsAndConditionsUrl = "https://example.com/new-terms-and-conditions"
      val event = TermsAndConditionsUrlChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), previousTermsAndConditionsUrl, newTermsAndConditionsUrl, adminEmail)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(adminEmail, applicationData.name, "terms and conditions URL", previousTermsAndConditionsUrl, newTermsAndConditionsUrl, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }

    "when receive a PrivacyPolicyUrlChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = "https://example.com/old-privacy-policy"
      val newPrivacyPolicyUrl = "https://example.com/new-privacy-policy"
      val event = PrivacyPolicyUrlChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), previousPrivacyPolicyUrl, newPrivacyPolicyUrl, adminEmail)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(adminEmail, applicationData.name, "privacy policy URL", previousPrivacyPolicyUrl, newPrivacyPolicyUrl, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }
  }
}
