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

import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util._

class NotificationServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with ApplicationTestData
    with UpliftRequestSamples
    with FixedClock {

  trait Setup extends EmailConnectorMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationId         = ApplicationId.random
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val applicationData: ApplicationData = anApplicationData(
      applicationId,
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val adminEmail     = "admin@example.com"
    val devHubUser     = CollaboratorActor(adminEmail)
    val gatekeeperUser = "gkuser"
    val oldAppName     = "old name"
    val newAppName     = "new name"
    val underTest      = new NotificationService(EmailConnectorMock.aMock)
  }

  "sendNotifications" should {
    "when receive a ProductionAppNameChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationName.thenReturnSuccess()
      val event = ProductionAppNameChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser),
        oldAppName,
        newAppName,
        adminEmail
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationName.verifyCalledWith(adminEmail, oldAppName, newAppName, Set(responsibleIndividual.emailAddress.value, loggedInUser))
    }

    "when receive a ProductionAppPrivacyPolicyLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = PrivacyPolicyLocation.Url("https://example.com/old-privacy-policy")
      val newPrivacyPolicyUrl      = PrivacyPolicyLocation.Url("https://example.com/new-privacy-policy")
      val event                    = ProductionAppPrivacyPolicyLocationChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        adminEmail,
        applicationData.name,
        "privacy policy URL",
        previousPrivacyPolicyUrl.value,
        newPrivacyPolicyUrl.value,
        Set(responsibleIndividual.emailAddress.value, loggedInUser)
      )
    }

    "when receive a ProductionLegacyAppPrivacyPolicyLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = "https://example.com/old-privacy-policy"
      val newPrivacyPolicyUrl      = "https://example.com/new-privacy-policy"
      val event                    = ProductionLegacyAppPrivacyPolicyLocationChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        adminEmail,
        applicationData.name,
        "privacy policy URL",
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl,
        Set(responsibleIndividual.emailAddress.value, loggedInUser)
      )
    }

    "when receive a ProductionAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousTermsAndConditionsUrl = TermsAndConditionsLocation.Url("https://example.com/old-terms-conds")
      val newTermsAndConditionsUrl      = TermsAndConditionsLocation.Url("https://example.com/new-terms-conds")
      val event                         = ProductionAppTermsConditionsLocationChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        adminEmail,
        applicationData.name,
        "terms and conditions URL",
        previousTermsAndConditionsUrl.value,
        newTermsAndConditionsUrl.value,
        Set(responsibleIndividual.emailAddress.value, loggedInUser)
      )
    }

    "when receive a ProductionLegacyAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousTermsAndConditionsUrl = "https://example.com/old-terms-conds"
      val newTermsAndConditionsUrl      = "https://example.com/new-terms-conds"
      val event                         = ProductionLegacyAppTermsConditionsLocationChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        FixedClock.now,
        devHubUser,
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        adminEmail,
        applicationData.name,
        "terms and conditions URL",
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl,
        Set(responsibleIndividual.emailAddress.value, loggedInUser)
      )
    }

    "when receive a ResponsibleIndividualVerificationStarted, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendVerifyResponsibleIndividualUpdateNotification.thenReturnSuccess()
      val event = ResponsibleIndividualVerificationStarted(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        "app name",
        FixedClock.now,
        CollaboratorActor("admin@example.com"),
        "admin name",
        "admin@example.com",
        "ri name",
        "ri@example.com",
        Submission.Id.random,
        1,
        ResponsibleIndividualVerificationId.random
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendVerifyResponsibleIndividualUpdateNotification.verifyCalledWith(
        event.responsibleIndividualName,
        event.responsibleIndividualEmail,
        event.applicationName,
        event.requestingAdminName,
        event.verificationId.value
      )
    }

    "when receive a ResponsibleIndividualChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfResponsibleIndividual.thenReturnSuccess()
      val event = ResponsibleIndividualChanged(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor("admin@example.com"),
        "old ri name",
        "oldri@example.com",
        "ri name",
        "ri@example.com",
        Submission.Id.random,
        1,
        "code12345678",
        "admin name",
        "admin@example.com"
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfResponsibleIndividual.verifyCalledWith(
        event.requestingAdminName,
        applicationData.name,
        event.previousResponsibleIndividualName,
        event.newResponsibleIndividualName,
        Set("oldri@example.com", loggedInUser)
      )
    }

    "when receive a ResponsibleIndividualChangedToSelf, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfResponsibleIndividual.thenReturnSuccess()
      val event = ResponsibleIndividualChangedToSelf(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor("admin@example.com"),
        "old ri name",
        "oldri@example.com",
        Submission.Id.random,
        1,
        "admin name",
        "admin@example.com"
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfResponsibleIndividual.verifyCalledWith(
        event.requestingAdminName,
        applicationData.name,
        event.previousResponsibleIndividualName,
        event.requestingAdminName,
        Set("oldri@example.com", loggedInUser)
      )
    }

    "when receive a ResponsibleIndividualDeclined, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendResponsibleIndividualDeclined.thenReturnSuccess()
      val event = ResponsibleIndividualDeclined(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor("admin@example.com"),
        "ri name",
        "ri@example.com",
        Submission.Id.random,
        1,
        "code12345678",
        "admin name",
        "admin@example.com"
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendResponsibleIndividualDeclined.verifyCalledWith(
        event.responsibleIndividualName,
        event.requestingAdminEmail,
        applicationData.name,
        event.requestingAdminName
      )
    }

    "when receive a ResponsibleIndividualDeclinedUpdate, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendResponsibleIndividualNotChanged.thenReturnSuccess()
      val event = ResponsibleIndividualDeclinedUpdate(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor("admin@example.com"),
        "ri name",
        "ri@example.com",
        Submission.Id.random,
        1,
        "code12345678",
        "admin name",
        "admin@example.com"
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendResponsibleIndividualNotChanged.verifyCalledWith(event.responsibleIndividualName, applicationData.name, Set(event.requestingAdminEmail))
    }

    "when receive a ResponsibleIndividualDidNotVerify, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.thenReturnSuccess()
      val event = ResponsibleIndividualDidNotVerify(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor("admin@example.com"),
        "ri name",
        "ri@example.com",
        Submission.Id.random,
        1,
        "code12345678",
        "admin name",
        "admin@example.com"
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.verifyCalledWith(
        event.responsibleIndividualName,
        event.requestingAdminEmail,
        applicationData.name,
        event.requestingAdminName
      )
    }

    "when receive a ClientSecretAdded, call the event handler and return successfully" in new Setup {
      val obfuscatedSecret     = "********cret"
      val requestingAdminEmail = "admin@example.com"
      EmailConnectorMock.SendAddedClientSecretNotification.thenReturnOk()
      val event                = ClientSecretAdded(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor(requestingAdminEmail),
        "secret",
        ClientSecret(obfuscatedSecret, FixedClock.now, hashedSecret = "hashed")
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendAddedClientSecretNotification.verifyCalledWith(
        requestingAdminEmail,
        obfuscatedSecret,
        applicationData.name,
        recipients = applicationData.admins.map(_.emailAddress)
      )
    }

    "when receive a ClientSecretRemoved, call the event handler and return successfully" in new Setup {
      val clientSecretId       = "the-id"
      val clientSecretName     = "********cret"
      val requestingAdminEmail = "dev@example.com"
      EmailConnectorMock.SendRemovedClientSecretNotification.thenReturnOk()
      val event                =
        ClientSecretRemoved(UpdateApplicationEvent.Id.random, ApplicationId.random, FixedClock.now, CollaboratorActor(requestingAdminEmail), clientSecretId, clientSecretName)

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendRemovedClientSecretNotification.verifyCalledWith(
        requestingAdminEmail,
        clientSecretName,
        applicationData.name,
        recipients = applicationData.admins.map(_.emailAddress)
      )
    }

    "when receive a AddCollaborator, call the event handler and return successfully" in new Setup {
      val adminsToEmail = Set("anAdmin@someCompany.com", "anotherdev@someCompany.com")

      EmailConnectorMock.SendCollaboratorAddedNotification.thenReturnSuccess()
      EmailConnectorMock.SendCollaboratorAddedConfirmation.thenReturnSuccess()

      val collaboratorEmail = "somedev@someCompany.com"
      val collaborator      = Collaborator(collaboratorEmail, Role.DEVELOPER, idOf(collaboratorEmail))
      val event             = CollaboratorAdded(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor("dev@example.com"),
        collaborator.userId,
        collaborator.emailAddress,
        collaborator.role,
        adminsToEmail
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)

      EmailConnectorMock.SendCollaboratorAddedNotification.verifyCalledWith(
        collaboratorEmail,
        collaborator.role,
        applicationData.name,
        recipients = adminsToEmail
      )

      EmailConnectorMock.SendCollaboratorAddedConfirmation
        .verifyCalledWith(collaborator.role, applicationData.name, recipients = Set(collaboratorEmail))

    }

    "when receive a RemoveCollaborator, call the event handler and return successfully" in new Setup {
      val adminsToEmail = Set("anAdmin@someCompany.com", "anotherdev@someCompany.com")

      EmailConnectorMock.SendCollaboratorRemovedNotification.thenReturnSuccess()
      EmailConnectorMock.SendCollaboratorRemovedConfirmation.thenReturnSuccess()

      val collaboratorEmail = "somedev@someCompany.com"
      val collaborator      = Collaborator(collaboratorEmail, Role.DEVELOPER, idOf(collaboratorEmail))
      val event             = CollaboratorRemoved(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        FixedClock.now,
        CollaboratorActor("dev@example.com"),
        collaborator.userId,
        collaborator.emailAddress,
        collaborator.role,
        true,
        adminsToEmail
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)

      EmailConnectorMock.SendCollaboratorRemovedNotification.verifyCalledWith(
        collaboratorEmail,
        applicationData.name,
        recipients = adminsToEmail
      )

      EmailConnectorMock.SendCollaboratorRemovedConfirmation
        .verifyCalledWith("dev@example.com", applicationData.name, recipients = Set(collaboratorEmail))

    }

    "when receive a ApplicationDeletedByGatekeeper, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendApplicationDeletedNotification.thenReturnSuccess()
      val event = ApplicationDeletedByGatekeeper(
        UpdateApplicationEvent.Id.random,
        applicationData.id,
        FixedClock.now,
        GatekeeperUserActor("gatekeeperuser"),
        ClientId("clientId"),
        "wso2AppName",
        "reasons",
        "admin@example.com"
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendApplicationDeletedNotification.verifyCalledWith(applicationData.name, event.applicationId, event.requestingAdminEmail, Set(loggedInUser))
    }

    "when receive a ProductionCredentialsApplicationDeleted, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendProductionCredentialsRequestExpired.thenReturnSuccess()
      val event = ProductionCredentialsApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationData.id,
        FixedClock.now,
        GatekeeperUserActor("gatekeeperuser"),
        ClientId("clientId"),
        "wso2AppName",
        "reasons"
      )

      val result = await(underTest.sendNotifications(applicationData, List(event)))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendProductionCredentialsRequestExpired.verifyCalledWith(applicationData.name, Set(loggedInUser))
    }
  }
}
