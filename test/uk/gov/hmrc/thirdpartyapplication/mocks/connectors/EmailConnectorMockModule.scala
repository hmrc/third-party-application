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

package uk.gov.hmrc.thirdpartyapplication.mocks.connectors

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.Future.successful

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationName, Collaborator}
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

trait EmailConnectorMockModule extends MockitoSugar with ArgumentMatchersSugar {

  object EmailConnectorMock {
    val aMock: EmailConnector = mock[EmailConnector]

    def verify: EmailConnector = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode): EmailConnector = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions(): Unit = MockitoSugar.verifyZeroInteractions(aMock)

    object SendAddedClientSecretNotification {

      def thenReturnOk() = {
        when(aMock.sendAddedClientSecretNotification(*[LaxEmailAddress], *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalled(): Future[HasSucceeded] = {
        verify.sendAddedClientSecretNotification(*[LaxEmailAddress], *, *, *)(*)
      }

      def verifyCalledWith(actorEmailAddress: LaxEmailAddress, clientSecret: String, applicationName: String, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendAddedClientSecretNotification(eqTo(actorEmailAddress), eqTo(clientSecret), eqTo(applicationName), eqTo(recipients))(*)
      }
    }

    object SendRemovedClientSecretNotification {

      def thenReturnOk() = {
        when(aMock.sendRemovedClientSecretNotification(*[LaxEmailAddress], *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalled(): Future[HasSucceeded] = {
        verify.sendRemovedClientSecretNotification(*[LaxEmailAddress], *, *, *)(*)
      }

      def verifyCalledWith(actorEmailAddress: LaxEmailAddress, clientSecret: String, applicationName: String, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendRemovedClientSecretNotification(eqTo(actorEmailAddress), eqTo(clientSecret), eqTo(applicationName), eqTo(recipients))(*)
      }

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedClientSecretNotification(*[LaxEmailAddress], *, *, *)(*)
    }

    object SendApplicationApprovedAdminConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationApprovedAdminConfirmation(*[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, code: String, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendApplicationApprovedAdminConfirmation(eqTo(applicationName), eqTo(code), eqTo(recipients))(*)
      }
    }

    object SendApplicationApprovedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationApprovedNotification(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendApplicationApprovedNotification(eqTo(applicationName), eqTo(recipients))(*)
      }
    }

    object SendVerifyResponsibleIndividualNotification {

      def thenReturnSuccess() = {
        when(aMock.sendVerifyResponsibleIndividualNotification(*, *[LaxEmailAddress], *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(
          responsibleIndividualName: String,
          responsibleIndividualEmailAddress: LaxEmailAddress,
          applicationName: String,
          requesterName: String,
          verifyResponsibleIndividualUniqueId: String
        ) =
        verify.sendVerifyResponsibleIndividualNotification(
          eqTo(responsibleIndividualName),
          eqTo(responsibleIndividualEmailAddress),
          eqTo(applicationName),
          eqTo(requesterName),
          eqTo(verifyResponsibleIndividualUniqueId)
        )(*)
    }

    object SendVerifyResponsibleIndividualUpdateNotification {

      def thenReturnSuccess(): Unit = {
        when(aMock.sendVerifyResponsibleIndividualUpdateNotification(*, *[LaxEmailAddress], *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(
          responsibleIndividualName: String,
          responsibleIndividualEmailAddress: LaxEmailAddress,
          applicationName: String,
          requesterName: String,
          verifyResponsibleIndividualUniqueId: String
        ) =
        verify.sendVerifyResponsibleIndividualUpdateNotification(
          eqTo(responsibleIndividualName),
          eqTo(responsibleIndividualEmailAddress),
          eqTo(applicationName),
          eqTo(requesterName),
          eqTo(verifyResponsibleIndividualUniqueId)
        )(*)
    }

    object SendVerifyResponsibleIndividualReminderToAdmin {

      def thenReturnSuccess() = {
        when(aMock.sendVerifyResponsibleIndividualReminderToAdmin(*, *[LaxEmailAddress], *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: LaxEmailAddress, applicationName: ApplicationName, requesterName: String) =
        verify.sendVerifyResponsibleIndividualReminderToAdmin(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(*)
    }

    object SendResponsibleIndividualDidNotVerify {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDidNotVerify(*, *[LaxEmailAddress], *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: LaxEmailAddress, applicationName: ApplicationName, requesterName: String) =
        verify.sendResponsibleIndividualDidNotVerify(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(*)
    }

    object SendResponsibleIndividualDeclined {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDeclinedOrDidNotVerify(*, *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
        when(aMock.sendResponsibleIndividualDeclined(*, *[LaxEmailAddress], *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: LaxEmailAddress, applicationName: ApplicationName, requesterName: String) =
        verify.sendResponsibleIndividualDeclined(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(*)
    }

    object SendResponsibleIndividualDeclinedOrDidNotVerify {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDeclinedOrDidNotVerify(*, *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendResponsibleIndividualDeclinedOrDidNotVerify(eqTo(responsibleIndividualName), eqTo(applicationName), eqTo(recipients))(*)
    }

    object SendResponsibleIndividualNotChanged {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualNotChanged(*, *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendResponsibleIndividualNotChanged(eqTo(responsibleIndividualName), eqTo(applicationName), eqTo(recipients))(*)
    }

    object SendProductionCredentialsRequestExpiryWarning {

      def thenReturnSuccess() = {
        when(aMock.sendProductionCredentialsRequestExpiryWarning(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendProductionCredentialsRequestExpiryWarning(eqTo(applicationName), eqTo(recipients))(*)
    }

    object SendProductionCredentialsRequestExpired {

      def thenReturnSuccess() = {
        when(aMock.sendProductionCredentialsRequestExpired(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendProductionCredentialsRequestExpired(eqTo(applicationName), eqTo(recipients))(*)
    }

    object SendApplicationDeletedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationDeletedNotification(*[ApplicationName], *[ApplicationId], *[LaxEmailAddress], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, applicationId: ApplicationId, requesterEmail: LaxEmailAddress, recipients: Set[LaxEmailAddress]) =
        verify.sendApplicationDeletedNotification(eqTo(applicationName), eqTo(applicationId), eqTo(requesterEmail), eqTo(recipients))(*)
    }

    object SendChangeOfApplicationName {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationName(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, previousAppName: String, newAppName: String, recipients: Set[LaxEmailAddress]) =
        verify.sendChangeOfApplicationName(eqTo(requester), eqTo(previousAppName), eqTo(newAppName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationName(*, *, *, *)(*)
    }

    object SendChangeOfApplicationDetails {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationDetails(*, *[ApplicationName], *, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: ApplicationName, fieldName: String, previousValue: String, newValue: String, recipients: Set[LaxEmailAddress]) =
        verify.sendChangeOfApplicationDetails(eqTo(requester), eqTo(applicationName), eqTo(fieldName), eqTo(previousValue), eqTo(newValue), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationDetails(*, *[ApplicationName], *, *, *, *)(*)
    }

    object SendChangeOfApplicationDetailsNoValue {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationDetailsNoValue(*, *[ApplicationName], *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: ApplicationName, fieldName: String, recipients: Set[LaxEmailAddress]) =
        verify.sendChangeOfApplicationDetailsNoValue(eqTo(requester), eqTo(applicationName), eqTo(fieldName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationDetailsNoValue(*, *[ApplicationName], *, *)(*)
    }

    object SendChangeOfResponsibleIndividual {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfResponsibleIndividual(*, *[ApplicationName], *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(
          requester: String,
          applicationName: ApplicationName,
          previousResponsibleIndividual: String,
          newResponsibleIndividual: String,
          recipients: Set[LaxEmailAddress]
        ) =
        verify.sendChangeOfResponsibleIndividual(eqTo(requester), eqTo(applicationName), eqTo(previousResponsibleIndividual), eqTo(newResponsibleIndividual), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfResponsibleIndividual(*, *[ApplicationName], *, *, *)(*)
    }

    object SendCollaboratorAddedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendCollaboratorAddedNotification(*, *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(collaborator: Collaborator, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendCollaboratorAddedNotification(eqTo(collaborator), eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendCollaboratorAddedNotification(*, *[ApplicationName], *)(*)
    }

    object SendCollaboratorAddedConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendCollaboratorAddedConfirmation(*, *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(collaborator: Collaborator, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendCollaboratorAddedConfirmation(eqTo(collaborator), eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendCollaboratorAddedConfirmation(*, *[ApplicationName], *)(*)
    }

    object SendCollaboratorRemovedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendRemovedCollaboratorNotification(*[LaxEmailAddress], *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(deletedEmail: LaxEmailAddress, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendRemovedCollaboratorNotification(eqTo(deletedEmail), eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedCollaboratorNotification(*[LaxEmailAddress], *[ApplicationName], *)(*)
    }

    object SendCollaboratorRemovedConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendRemovedCollaboratorConfirmation(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendRemovedCollaboratorConfirmation(eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedCollaboratorConfirmation(*[ApplicationName], *)(*)
    }

    object SendNewTermsOfUseInvitation {
      def thenReturnSuccess() = when(aMock.sendNewTermsOfUseInvitation(*[Instant], *[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))

      def verifyCalledWith(dueBy: Instant, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendNewTermsOfUseInvitation(eqTo(dueBy), eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendNewTermsOfUseInvitation(*, *[ApplicationName], *)(*)
    }

    object SendNewTermsOfUseConfirmation {
      def thenReturnSuccess() = when(aMock.sendNewTermsOfUseConfirmation(*[ApplicationName], *)(*)).thenReturn(successful(HasSucceeded))

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendNewTermsOfUseConfirmation(eqTo(applicationName), eqTo(recipients))(*)
    }
  }
}
