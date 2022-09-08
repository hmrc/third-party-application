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

package uk.gov.hmrc.thirdpartyapplication.services.notifications

import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
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

    "when receive a ProductionAppPrivacyPolicyLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = PrivacyPolicyLocation.Url("https://example.com/old-privacy-policy")
      val newPrivacyPolicyUrl = PrivacyPolicyLocation.Url("https://example.com/new-privacy-policy")
      val event = ProductionAppPrivacyPolicyLocationChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), previousPrivacyPolicyUrl, newPrivacyPolicyUrl, adminEmail)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(adminEmail, applicationData.name, "privacy policy URL", previousPrivacyPolicyUrl.value, newPrivacyPolicyUrl.value, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }

    "when receive a ProductionLegacyAppPrivacyPolicyLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = "https://example.com/old-privacy-policy"
      val newPrivacyPolicyUrl = "https://example.com/new-privacy-policy"
      val event = ProductionLegacyAppPrivacyPolicyLocationChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), previousPrivacyPolicyUrl, newPrivacyPolicyUrl, adminEmail)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(adminEmail, applicationData.name, "privacy policy URL", previousPrivacyPolicyUrl, newPrivacyPolicyUrl, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }

    "when receive a ProductionAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousTermsAndConditionsUrl = TermsAndConditionsLocation.Url("https://example.com/old-terms-conds")
      val newTermsAndConditionsUrl = TermsAndConditionsLocation.Url("https://example.com/new-terms-conds")
      val event = ProductionAppTermsConditionsLocationChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), previousTermsAndConditionsUrl, newTermsAndConditionsUrl, adminEmail)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(adminEmail, applicationData.name, "terms and conditions URL", previousTermsAndConditionsUrl.value, newTermsAndConditionsUrl.value, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }

    "when receive a ProductionLegacyAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousTermsAndConditionsUrl = "https://example.com/old-terms-conds"
      val newTermsAndConditionsUrl = "https://example.com/new-terms-conds"
      val event = ProductionLegacyAppTermsConditionsLocationChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), previousTermsAndConditionsUrl, newTermsAndConditionsUrl, adminEmail)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(adminEmail, applicationData.name, "terms and conditions URL", previousTermsAndConditionsUrl, newTermsAndConditionsUrl, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }

    "when receive a ResponsibleIndividualVerificationStarted, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendVerifyResponsibleIndividualUpdateNotification.thenReturnSuccess()
      val event = ResponsibleIndividualVerificationStarted(UpdateApplicationEvent.Id.random, ApplicationId.random, "app name", LocalDateTime.now(),
        CollaboratorActor("admin@example.com"), "admin name", "admin@example.com",
        "ri name", "ri@example.com", Submission.Id.random, 1, ResponsibleIndividualVerificationId.random)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendVerifyResponsibleIndividualUpdateNotification.verifyCalledWith(event.responsibleIndividualName, event.responsibleIndividualEmail, event.applicationName, event.requestingAdminName, event.verificationId.value)
    }

    "when receive a ResponsibleIndividualChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfResponsibleIndividual.thenReturnSuccess()
      val event = ResponsibleIndividualChanged(UpdateApplicationEvent.Id.random, ApplicationId.random, LocalDateTime.now(),
        CollaboratorActor("admin@example.com"), "old ri name", "oldri@example.com",
        "ri name", "ri@example.com", Submission.Id.random, 1, "code12345678", "admin name", "admin@example.com")

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfResponsibleIndividual.verifyCalledWith(event.requestingAdminName, applicationData.name, event.previousResponsibleIndividualName, event.newResponsibleIndividualName, Set("oldri@example.com", loggedInUser))
    }

    "when receive a ResponsibleIndividualDeclined, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendResponsibleIndividualDeclined.thenReturnSuccess()
      val event = ResponsibleIndividualDeclined(UpdateApplicationEvent.Id.random, ApplicationId.random, LocalDateTime.now(),
        CollaboratorActor("admin@example.com"), 
        "ri name", "ri@example.com", Submission.Id.random, 1, "code12345678", "admin name", "admin@example.com")

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendResponsibleIndividualDeclined.verifyCalledWith(event.responsibleIndividualName, event.requestingAdminEmail, applicationData.name, event.requestingAdminName)
    }
  }
}
