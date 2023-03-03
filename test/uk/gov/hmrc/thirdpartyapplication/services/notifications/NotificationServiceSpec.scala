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

import scala.concurrent.ExecutionContext.Implicits.global

import cats.data.NonEmptyList
import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientId, PrivacyPolicyLocations, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util.{FixedClock, _}

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
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val applicationData: ApplicationData = anApplicationData(
      applicationId,
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val collaboratorEmails = applicationData.collaborators.map(_.emailAddress)

    val gatekeeperUser = "gkuser"
    val oldAppName     = "old name"
    val newAppName     = "new name"
    val underTest      = new NotificationService(EmailConnectorMock.aMock)
  }

  "sendNotifications" should {
    "when receive a ProductionAppNameChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationName.thenReturnSuccess()
      val event = ProductionAppNameChangedEvent(
        EventId.random,
        applicationId,
        FixedClock.instant,
        gatekeeperActor,
        oldAppName,
        newAppName,
        otherAdminCollaborator.emailAddress
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)

      EmailConnectorMock.SendChangeOfApplicationName.verifyCalledWith(anAdminEmail.text, oldAppName, newAppName, collaboratorEmails + responsibleIndividual.emailAddress)
    }

    "when receive a ProductionAppPrivacyPolicyLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = PrivacyPolicyLocations.Url("https://example.com/old-privacy-policy")
      val newPrivacyPolicyUrl      = PrivacyPolicyLocations.Url("https://example.com/new-privacy-policy")
      val event                    = ProductionAppPrivacyPolicyLocationChanged(
        EventId.random,
        applicationId,
        FixedClock.instant,
        otherAdminAsActor,
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "privacy policy URL",
        previousPrivacyPolicyUrl.value,
        newPrivacyPolicyUrl.value,
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ProductionLegacyAppPrivacyPolicyLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousPrivacyPolicyUrl = "https://example.com/old-privacy-policy"
      val newPrivacyPolicyUrl      = "https://example.com/new-privacy-policy"
      val event                    = ProductionLegacyAppPrivacyPolicyLocationChanged(
        EventId.random,
        applicationId,
        FixedClock.instant,
        otherAdminAsActor,
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "privacy policy URL",
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl,
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ProductionAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousTermsAndConditionsUrl = TermsAndConditionsLocations.Url("https://example.com/old-terms-conds")
      val newTermsAndConditionsUrl      = TermsAndConditionsLocations.Url("https://example.com/new-terms-conds")
      val event                         = ProductionAppTermsConditionsLocationChanged(
        EventId.random,
        applicationId,
        FixedClock.instant,
        otherAdminAsActor,
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "terms and conditions URL",
        previousTermsAndConditionsUrl.value,
        newTermsAndConditionsUrl.value,
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ProductionLegacyAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetails.thenReturnSuccess()
      val previousTermsAndConditionsUrl = "https://example.com/old-terms-conds"
      val newTermsAndConditionsUrl      = "https://example.com/new-terms-conds"
      val event                         = ProductionLegacyAppTermsConditionsLocationChanged(
        EventId.random,
        applicationId,
        FixedClock.instant,
        otherAdminAsActor,
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetails.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "terms and conditions URL",
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl,
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ResponsibleIndividualVerificationStarted, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendVerifyResponsibleIndividualUpdateNotification.thenReturnSuccess()
      val event = ResponsibleIndividualVerificationStarted(
        EventId.random,
        ApplicationId.random,
        "app name",
        FixedClock.instant,
        otherAdminAsActor,
        "admin name",
        "admin@example.com".toLaxEmail,
        "ri name",
        "ri@example.com".toLaxEmail,
        SubmissionId.random,
        1,
        ResponsibleIndividualVerificationId.random.value
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendVerifyResponsibleIndividualUpdateNotification.verifyCalledWith(
        event.responsibleIndividualName,
        event.responsibleIndividualEmail,
        event.applicationName,
        event.requestingAdminName,
        event.verificationId
      )
    }

    "when receive a ResponsibleIndividualChanged, call the event handler and return successfully" in new Setup {
      val newRIemail = "ri@example.com".toLaxEmail
      val oldRIemail = "oldri@example.com".toLaxEmail

      EmailConnectorMock.SendChangeOfResponsibleIndividual.thenReturnSuccess()
      val event = ResponsibleIndividualChanged(
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        otherAdminAsActor,
        "old ri name",
        oldRIemail,
        "ri name",
        newRIemail,
        SubmissionId.random,
        1,
        "code12345678",
        "admin name",
        otherAdminAsActor.email
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfResponsibleIndividual.verifyCalledWith(
        event.requestingAdminName,
        applicationData.name,
        event.previousResponsibleIndividualName,
        event.newResponsibleIndividualName,
        collaboratorEmails + oldRIemail
      )
    }

    "when receive a ResponsibleIndividualChangedToSelf, call the event handler and return successfully" in new Setup {
      val oldRIemail = "oldri@example.com".toLaxEmail

      EmailConnectorMock.SendChangeOfResponsibleIndividual.thenReturnSuccess()
      val event = ResponsibleIndividualChangedToSelf(
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        otherAdminAsActor,
        "old ri name",
        oldRIemail,
        SubmissionId.random,
        1,
        "admin name",
        "admin@example.com".toLaxEmail
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfResponsibleIndividual.verifyCalledWith(
        event.requestingAdminName,
        applicationData.name,
        event.previousResponsibleIndividualName,
        event.requestingAdminName,
        collaboratorEmails + oldRIemail
      )
    }

    "when receive a ResponsibleIndividualDeclined, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendResponsibleIndividualDeclined.thenReturnSuccess()
      val event = ResponsibleIndividualDeclined(
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        otherAdminAsActor,
        "ri name",
        "ri@example.com".toLaxEmail,
        SubmissionId.random,
        1,
        "code12345678",
        "admin name",
        "admin@example.com".toLaxEmail
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
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
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        otherAdminAsActor,
        "ri name",
        "ri@example.com".toLaxEmail,
        SubmissionId.random,
        1,
        "code12345678",
        "admin name",
        "admin@example.com".toLaxEmail
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendResponsibleIndividualNotChanged.verifyCalledWith(event.responsibleIndividualName, applicationData.name, Set(event.requestingAdminEmail))
    }

    "when receive a ResponsibleIndividualDidNotVerify, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.thenReturnSuccess()
      val event = ResponsibleIndividualDidNotVerify(
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        otherAdminAsActor,
        "ri name",
        "ri@example.com".toLaxEmail,
        SubmissionId.random,
        1,
        "code12345678",
        "admin name",
        "admin@example.com".toLaxEmail
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
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
      val requestingAdminEmail = "admin@example.com".toLaxEmail
      EmailConnectorMock.SendAddedClientSecretNotification.thenReturnOk()
      val event                = ClientSecretAddedV2(
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        Actors.AppCollaborator(requestingAdminEmail),
        "someClientSecretId",
        obfuscatedSecret
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
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
      val requestingAdminEmail = "dev@example.com".toLaxEmail
      EmailConnectorMock.SendRemovedClientSecretNotification.thenReturnOk()
      val event                =
        ClientSecretRemovedV2(EventId.random, ApplicationId.random, FixedClock.instant, Actors.AppCollaborator(requestingAdminEmail), clientSecretId, clientSecretName)

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendRemovedClientSecretNotification.verifyCalledWith(
        requestingAdminEmail,
        clientSecretName,
        applicationData.name,
        recipients = applicationData.admins.map(_.emailAddress)
      )
    }

    "when receive a AddCollaborator, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendCollaboratorAddedNotification.thenReturnSuccess()
      EmailConnectorMock.SendCollaboratorAddedConfirmation.thenReturnSuccess()

      val newCollaborator = "somedev@someCompany.com".developer()

      val event                 = CollaboratorAddedV2(
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        developerAsActor,
        newCollaborator,
        Set.empty
      )
      val verifiedCollaborators = Set(otherAdminCollaborator.emailAddress, developerCollaborator.emailAddress)

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), verifiedCollaborators))
      result shouldBe List(HasSucceeded)

      EmailConnectorMock.SendCollaboratorAddedNotification.verifyCalledWith(
        newCollaborator,
        applicationData.name,
        recipients = Set(otherAdminCollaborator.emailAddress) // Developer is not notified, nor is the unverified admin
      )

      EmailConnectorMock.SendCollaboratorAddedConfirmation
        .verifyCalledWith(newCollaborator, applicationData.name, recipients = Set(newCollaborator.emailAddress))

    }

    "when receive a RemoveCollaborator, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendCollaboratorRemovedNotification.thenReturnSuccess()
      EmailConnectorMock.SendCollaboratorRemovedConfirmation.thenReturnSuccess()

      val removedCollaborator = developerCollaborator

      val event = CollaboratorRemovedV2(
        EventId.random,
        ApplicationId.random,
        FixedClock.instant,
        otherAdminAsActor,
        removedCollaborator,
        Set.empty
      )

      val verifiedCollaborators = Set(otherAdminCollaborator.emailAddress, developerCollaborator.emailAddress)

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), verifiedCollaborators))
      result shouldBe List(HasSucceeded)

      EmailConnectorMock.SendCollaboratorRemovedNotification.verifyCalledWith(
        removedCollaborator.emailAddress,
        applicationData.name,
        recipients = Set(otherAdminCollaborator.emailAddress)
      )

      EmailConnectorMock.SendCollaboratorRemovedConfirmation
        .verifyCalledWith(applicationData.name, recipients = Set(removedCollaborator.emailAddress))

    }

    "when receive a ApplicationDeletedByGatekeeper, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendApplicationDeletedNotification.thenReturnSuccess()
      val event = ApplicationDeletedByGatekeeper(
        EventId.random,
        applicationData.id,
        FixedClock.instant,
        gatekeeperActor,
        ClientId("clientId"),
        "wso2AppName",
        "reasons",
        otherAdminAsActor.email
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendApplicationDeletedNotification.verifyCalledWith(applicationData.name, event.applicationId, event.requestingAdminEmail, collaboratorEmails)
    }

    "when receive a ProductionCredentialsApplicationDeleted, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendProductionCredentialsRequestExpired.thenReturnSuccess()
      val event = ProductionCredentialsApplicationDeleted(
        EventId.random,
        applicationData.id,
        FixedClock.instant,
        gatekeeperActor,
        ClientId("clientId"),
        "wso2AppName",
        "reasons"
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendProductionCredentialsRequestExpired.verifyCalledWith(applicationData.name, collaboratorEmails)
    }
  }
}
