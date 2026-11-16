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
        when(aMock.sendAddedClientSecretNotification(*[LaxEmailAddress], *, *, *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalled(): Future[HasSucceeded] = {
        verify.sendAddedClientSecretNotification(*[LaxEmailAddress], *, *, *)(using *)
      }

      def verifyCalledWith(actorEmailAddress: LaxEmailAddress, clientSecret: String, applicationName: String, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendAddedClientSecretNotification(eqTo(actorEmailAddress), eqTo(clientSecret), eqTo(applicationName), eqTo(recipients))(using *)
      }
    }

    object SendRemovedClientSecretNotification {

      def thenReturnOk() = {
        when(aMock.sendRemovedClientSecretNotification(*[LaxEmailAddress], *, *, *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalled(): Future[HasSucceeded] = {
        verify.sendRemovedClientSecretNotification(*[LaxEmailAddress], *, *, *)(using *)
      }

      def verifyCalledWith(actorEmailAddress: LaxEmailAddress, clientSecret: String, applicationName: String, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendRemovedClientSecretNotification(eqTo(actorEmailAddress), eqTo(clientSecret), eqTo(applicationName), eqTo(recipients))(using *)
      }

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedClientSecretNotification(*[LaxEmailAddress], *, *, *)(using *)
    }

    object SendApplicationApprovedAdminConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationApprovedAdminConfirmation(*[ApplicationName], *, *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, code: String, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendApplicationApprovedAdminConfirmation(eqTo(applicationName), eqTo(code), eqTo(recipients))(using *)
      }
    }

    object SendApplicationApprovedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationApprovedNotification(*[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]): Future[HasSucceeded] = {
        verify.sendApplicationApprovedNotification(eqTo(applicationName), eqTo(recipients))(using *)
      }
    }

    object SendVerifyResponsibleIndividualNotification {

      def thenReturnSuccess() = {
        when(aMock.sendVerifyResponsibleIndividualNotification(*, *[LaxEmailAddress], *, *, *)(using *)).thenReturn(successful(HasSucceeded))
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
        )(using *)
    }

    object SendVerifyResponsibleIndividualUpdateNotification {

      def thenReturnSuccess(): Unit = {
        when(aMock.sendVerifyResponsibleIndividualUpdateNotification(*, *[LaxEmailAddress], *, *, *)(using *)).thenReturn(successful(HasSucceeded))
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
        )(using *)
    }

    object SendVerifyResponsibleIndividualReminderToAdmin {

      def thenReturnSuccess() = {
        when(aMock.sendVerifyResponsibleIndividualReminderToAdmin(*, *[LaxEmailAddress], *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: LaxEmailAddress, applicationName: ApplicationName, requesterName: String) =
        verify.sendVerifyResponsibleIndividualReminderToAdmin(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(using *)
    }

    object SendResponsibleIndividualDidNotVerify {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDidNotVerify(*, *[LaxEmailAddress], *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: LaxEmailAddress, applicationName: ApplicationName, requesterName: String) =
        verify.sendResponsibleIndividualDidNotVerify(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(using *)
    }

    object SendResponsibleIndividualDeclined {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDeclinedOrDidNotVerify(*, *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
        when(aMock.sendResponsibleIndividualDeclined(*, *[LaxEmailAddress], *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: LaxEmailAddress, applicationName: ApplicationName, requesterName: String) =
        verify.sendResponsibleIndividualDeclined(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(using *)
    }

    object SendResponsibleIndividualDeclinedOrDidNotVerify {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDeclinedOrDidNotVerify(*, *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendResponsibleIndividualDeclinedOrDidNotVerify(eqTo(responsibleIndividualName), eqTo(applicationName), eqTo(recipients))(using *)
    }

    object SendResponsibleIndividualNotChanged {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualNotChanged(*, *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendResponsibleIndividualNotChanged(eqTo(responsibleIndividualName), eqTo(applicationName), eqTo(recipients))(using *)
    }

    object SendProductionCredentialsRequestExpiryWarning {

      def thenReturnSuccess() = {
        when(aMock.sendProductionCredentialsRequestExpiryWarning(*[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendProductionCredentialsRequestExpiryWarning(eqTo(applicationName), eqTo(recipients))(using *)
    }

    object SendProductionCredentialsRequestExpired {

      def thenReturnSuccess() = {
        when(aMock.sendProductionCredentialsRequestExpired(*[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendProductionCredentialsRequestExpired(eqTo(applicationName), eqTo(recipients))(using *)
    }

    object SendApplicationDeletedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationDeletedNotification(*[ApplicationName], *[ApplicationId], *[LaxEmailAddress], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, applicationId: ApplicationId, requesterEmail: LaxEmailAddress, recipients: Set[LaxEmailAddress]) =
        verify.sendApplicationDeletedNotification(eqTo(applicationName), eqTo(applicationId), eqTo(requesterEmail), eqTo(recipients))(using *)
    }

    object SendChangeOfApplicationName {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationName(*, *, *, *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, previousAppName: String, newAppName: String, recipients: Set[LaxEmailAddress]) =
        verify.sendChangeOfApplicationName(eqTo(requester), eqTo(previousAppName), eqTo(newAppName), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationName(*, *, *, *)(using *)
    }

    object SendChangeOfApplicationDetails {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationDetails(*, *[ApplicationName], *, *, *, *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: ApplicationName, fieldName: String, previousValue: String, newValue: String, recipients: Set[LaxEmailAddress]) =
        verify.sendChangeOfApplicationDetails(eqTo(requester), eqTo(applicationName), eqTo(fieldName), eqTo(previousValue), eqTo(newValue), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationDetails(*, *[ApplicationName], *, *, *, *)(using *)
    }

    object SendChangeOfApplicationDetailsNoValue {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationDetailsNoValue(*, *[ApplicationName], *, *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: ApplicationName, fieldName: String, recipients: Set[LaxEmailAddress]) =
        verify.sendChangeOfApplicationDetailsNoValue(eqTo(requester), eqTo(applicationName), eqTo(fieldName), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationDetailsNoValue(*, *[ApplicationName], *, *)(using *)
    }

    object SendChangeOfResponsibleIndividual {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfResponsibleIndividual(*, *[ApplicationName], *, *, *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(
          requester: String,
          applicationName: ApplicationName,
          previousResponsibleIndividual: String,
          newResponsibleIndividual: String,
          recipients: Set[LaxEmailAddress]
        ) =
        verify.sendChangeOfResponsibleIndividual(eqTo(requester), eqTo(applicationName), eqTo(previousResponsibleIndividual), eqTo(newResponsibleIndividual), eqTo(recipients))(
          using *
        )

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfResponsibleIndividual(*, *[ApplicationName], *, *, *)(using *)
    }

    object SendCollaboratorAddedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendCollaboratorAddedNotification(*, *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(collaborator: Collaborator, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendCollaboratorAddedNotification(eqTo(collaborator), eqTo(applicationName), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendCollaboratorAddedNotification(*, *[ApplicationName], *)(using *)
    }

    object SendCollaboratorAddedConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendCollaboratorAddedConfirmation(*, *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(collaborator: Collaborator, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendCollaboratorAddedConfirmation(eqTo(collaborator), eqTo(applicationName), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendCollaboratorAddedConfirmation(*, *[ApplicationName], *)(using *)
    }

    object SendCollaboratorRemovedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendRemovedCollaboratorNotification(*[LaxEmailAddress], *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(deletedEmail: LaxEmailAddress, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendRemovedCollaboratorNotification(eqTo(deletedEmail), eqTo(applicationName), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedCollaboratorNotification(*[LaxEmailAddress], *[ApplicationName], *)(using *)
    }

    object SendCollaboratorRemovedConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendRemovedCollaboratorConfirmation(*[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendRemovedCollaboratorConfirmation(eqTo(applicationName), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedCollaboratorConfirmation(*[ApplicationName], *)(using *)
    }

    object SendNewTermsOfUseInvitation {
      def thenReturnSuccess() = when(aMock.sendNewTermsOfUseInvitation(*[Instant], *[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))

      def verifyCalledWith(dueBy: Instant, applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendNewTermsOfUseInvitation(eqTo(dueBy), eqTo(applicationName), eqTo(recipients))(using *)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendNewTermsOfUseInvitation(*, *[ApplicationName], *)(using *)
    }

    object SendNewTermsOfUseConfirmation {
      def thenReturnSuccess() = when(aMock.sendNewTermsOfUseConfirmation(*[ApplicationName], *)(using *)).thenReturn(successful(HasSucceeded))

      def verifyCalledWith(applicationName: ApplicationName, recipients: Set[LaxEmailAddress]) =
        verify.sendNewTermsOfUseConfirmation(eqTo(applicationName), eqTo(recipients))(using *)
    }
  }
}
