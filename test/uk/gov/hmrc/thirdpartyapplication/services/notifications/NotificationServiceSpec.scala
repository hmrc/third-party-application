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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{PrivacyPolicyLocations, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
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
    with UpliftRequestSamples {

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
      access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData))
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
      val event = ApplicationEvents.ProductionAppNameChangedEvent(
        EventId.random,
        applicationId,
        instant,
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
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.thenReturnSuccess()
      val previousPrivacyPolicyUrl = PrivacyPolicyLocations.Url("https://example.com/old-privacy-policy")
      val newPrivacyPolicyUrl      = PrivacyPolicyLocations.Url("https://example.com/new-privacy-policy")
      val event                    = ApplicationEvents.ProductionAppPrivacyPolicyLocationChanged(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "privacy policy URL",
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ProductionLegacyAppPrivacyPolicyLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.thenReturnSuccess()
      val previousPrivacyPolicyUrl = "https://example.com/old-privacy-policy"
      val newPrivacyPolicyUrl      = "https://example.com/new-privacy-policy"
      val event                    = ApplicationEvents.ProductionLegacyAppPrivacyPolicyLocationChanged(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        previousPrivacyPolicyUrl,
        newPrivacyPolicyUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "privacy policy URL",
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ProductionAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.thenReturnSuccess()
      val previousTermsAndConditionsUrl = TermsAndConditionsLocations.Url("https://example.com/old-terms-conds")
      val newTermsAndConditionsUrl      = TermsAndConditionsLocations.Url("https://example.com/new-terms-conds")
      val event                         = ApplicationEvents.ProductionAppTermsConditionsLocationChanged(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "terms and conditions URL",
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ProductionLegacyAppTermsConditionsLocationChanged, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.thenReturnSuccess()
      val previousTermsAndConditionsUrl = "https://example.com/old-terms-conds"
      val newTermsAndConditionsUrl      = "https://example.com/new-terms-conds"
      val event                         = ApplicationEvents.ProductionLegacyAppTermsConditionsLocationChanged(
        EventId.random,
        applicationId,
        instant,
        otherAdminAsActor,
        previousTermsAndConditionsUrl,
        newTermsAndConditionsUrl
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendChangeOfApplicationDetailsNoValue.verifyCalledWith(
        anAdminEmail.text,
        applicationData.name,
        "terms and conditions URL",
        collaboratorEmails + responsibleIndividual.emailAddress
      )
    }

    "when receive a ResponsibleIndividualVerificationStarted, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendVerifyResponsibleIndividualUpdateNotification.thenReturnSuccess()
      val event = ApplicationEvents.ResponsibleIndividualVerificationStarted(
        EventId.random,
        ApplicationId.random,
        instant,
        otherAdminAsActor,
        "app name",
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
      val event = ApplicationEvents.ResponsibleIndividualChanged(
        EventId.random,
        ApplicationId.random,
        instant,
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
      val event = ApplicationEvents.ResponsibleIndividualChangedToSelf(
        EventId.random,
        ApplicationId.random,
        instant,
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
      val event = ApplicationEvents.ResponsibleIndividualDeclined(
        EventId.random,
        ApplicationId.random,
        instant,
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
      val event = ApplicationEvents.ResponsibleIndividualDeclinedUpdate(
        EventId.random,
        ApplicationId.random,
        instant,
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
      val event = ApplicationEvents.ResponsibleIndividualDidNotVerify(
        EventId.random,
        ApplicationId.random,
        instant,
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

    "when receive a ResponsibleIndividualDeclinedOrDidNotVerify, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendResponsibleIndividualDeclinedOrDidNotVerify.thenReturnSuccess()
      val event = ApplicationEvents.ResponsibleIndividualDeclinedOrDidNotVerify(
        EventId.random,
        ApplicationId.random,
        instant,
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
      EmailConnectorMock.SendResponsibleIndividualDeclinedOrDidNotVerify.verifyCalledWith(
        event.responsibleIndividualName,
        applicationData.name,
        recipients = applicationData.admins.map(_.emailAddress)
      )
    }

    "when receive a TermsOfUsePassed, call the event handler and return successfully" in new Setup {
      EmailConnectorMock.SendNewTermsOfUseConfirmation.thenReturnSuccess()
      val event = ApplicationEvents.TermsOfUsePassed(
        EventId.random,
        ApplicationId.random,
        instant,
        otherAdminAsActor,
        SubmissionId.random,
        1
      )

      val result = await(underTest.sendNotifications(applicationData, NonEmptyList.one(event), Set.empty))
      result shouldBe List(HasSucceeded)
      EmailConnectorMock.SendNewTermsOfUseConfirmation.verifyCalledWith(
        applicationData.name,
        recipients = applicationData.admins.map(_.emailAddress)
      )
    }

    "when receive a ClientSecretAdded, call the event handler and return successfully" in new Setup {
      val obfuscatedSecret     = "********cret"
      val requestingAdminEmail = "admin@example.com".toLaxEmail
      EmailConnectorMock.SendAddedClientSecretNotification.thenReturnOk()
      val event                = ApplicationEvents.ClientSecretAddedV2(
        EventId.random,
        ApplicationId.random,
        instant,
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
        ApplicationEvents.ClientSecretRemovedV2(EventId.random, ApplicationId.random, instant, Actors.AppCollaborator(requestingAdminEmail), clientSecretId, clientSecretName)

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

      val event                 = ApplicationEvents.CollaboratorAddedV2(
        EventId.random,
        ApplicationId.random,
        instant,
        developerAsActor,
        newCollaborator
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

      val event = ApplicationEvents.CollaboratorRemovedV2(
        EventId.random,
        ApplicationId.random,
        instant,
        otherAdminAsActor,
        removedCollaborator
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
      val event = ApplicationEvents.ApplicationDeletedByGatekeeper(
        EventId.random,
        applicationData.id,
        instant,
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
      val event = ApplicationEvents.ProductionCredentialsApplicationDeleted(
        EventId.random,
        applicationData.id,
        instant,
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
