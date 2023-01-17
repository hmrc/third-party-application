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

import scala.concurrent.Future
import scala.concurrent.Future.successful

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role.Role
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

trait EmailConnectorMockModule extends MockitoSugar with ArgumentMatchersSugar {

  object EmailConnectorMock {
    val aMock: EmailConnector = mock[EmailConnector]

    def verify: EmailConnector = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode): EmailConnector = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions(): Unit = MockitoSugar.verifyZeroInteractions(aMock)

    object SendAddedClientSecretNotification {

      def thenReturnOk() = {
        when(aMock.sendAddedClientSecretNotification(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalled(): Future[HasSucceeded] = {
        verify.sendAddedClientSecretNotification(*, *, *, *)(*)
      }

      def verifyCalledWith(actorEmailAddress: String, clientSecret: String, applicationName: String, recipients: Set[String]): Future[HasSucceeded] = {
        verify.sendAddedClientSecretNotification(eqTo(actorEmailAddress), eqTo(clientSecret), eqTo(applicationName), eqTo(recipients))(*)
      }
    }

    object SendRemovedClientSecretNotification {

      def thenReturnOk() = {
        when(aMock.sendRemovedClientSecretNotification(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalled(): Future[HasSucceeded] = {
        verify.sendRemovedClientSecretNotification(*, *, *, *)(*)
      }

      def verifyCalledWith(actorEmailAddress: String, clientSecret: String, applicationName: String, recipients: Set[String]): Future[HasSucceeded] = {
        verify.sendRemovedClientSecretNotification(eqTo(actorEmailAddress), eqTo(clientSecret), eqTo(applicationName), eqTo(recipients))(*)
      }

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedClientSecretNotification(*, *, *, *)(*)
    }

    object SendApplicationApprovedAdminConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationApprovedAdminConfirmation(*, *, *)(*)).thenReturn(successful(HasSucceeded))
      }
    }

    object SendVerifyResponsibleIndividualNotification {

      def thenReturnSuccess() = {
        when(aMock.sendVerifyResponsibleIndividualNotification(*, *, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(
          responsibleIndividualName: String,
          responsibleIndividualEmailAddress: String,
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
        when(aMock.sendVerifyResponsibleIndividualUpdateNotification(*, *, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(
          responsibleIndividualName: String,
          responsibleIndividualEmailAddress: String,
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
        when(aMock.sendVerifyResponsibleIndividualReminderToAdmin(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: String, applicationName: String, requesterName: String) =
        verify.sendVerifyResponsibleIndividualReminderToAdmin(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(*)
    }

    object SendResponsibleIndividualDidNotVerify {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDidNotVerify(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: String, applicationName: String, requesterName: String) =
        verify.sendResponsibleIndividualDidNotVerify(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(*)
    }

    object SendResponsibleIndividualDeclined {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualDeclined(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, adminEmailAddress: String, applicationName: String, requesterName: String) =
        verify.sendResponsibleIndividualDeclined(eqTo(responsibleIndividualName), eqTo(adminEmailAddress), eqTo(applicationName), eqTo(requesterName))(*)
    }

    object SendResponsibleIndividualNotChanged {

      def thenReturnSuccess() = {
        when(aMock.sendResponsibleIndividualNotChanged(*, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(responsibleIndividualName: String, applicationName: String, recipients: Set[String]) =
        verify.sendResponsibleIndividualNotChanged(eqTo(responsibleIndividualName), eqTo(applicationName), eqTo(recipients))(*)
    }

    object SendProductionCredentialsRequestExpiryWarning {

      def thenReturnSuccess() = {
        when(aMock.sendProductionCredentialsRequestExpiryWarning(*, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: String, recipients: Set[String]) =
        verify.sendProductionCredentialsRequestExpiryWarning(eqTo(applicationName), eqTo(recipients))(*)
    }

    object SendProductionCredentialsRequestExpired {

      def thenReturnSuccess() = {
        when(aMock.sendProductionCredentialsRequestExpired(*, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: String, recipients: Set[String]) =
        verify.sendProductionCredentialsRequestExpired(eqTo(applicationName), eqTo(recipients))(*)
    }

    object SendApplicationDeletedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendApplicationDeletedNotification(*, *[ApplicationId], *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(applicationName: String, applicationId: ApplicationId, requesterEmail: String, recipients: Set[String]) =
        verify.sendApplicationDeletedNotification(eqTo(applicationName), eqTo(applicationId), eqTo(requesterEmail), eqTo(recipients))(*)
    }

    object SendChangeOfApplicationName {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationName(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, previousAppName: String, newAppName: String, recipients: Set[String]) =
        verify.sendChangeOfApplicationName(eqTo(requester), eqTo(previousAppName), eqTo(newAppName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationName(*, *, *, *)(*)
    }

    object SendChangeOfApplicationDetails {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfApplicationDetails(*, *, *, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: String, fieldName: String, previousValue: String, newValue: String, recipients: Set[String]) =
        verify.sendChangeOfApplicationDetails(eqTo(requester), eqTo(applicationName), eqTo(fieldName), eqTo(previousValue), eqTo(newValue), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfApplicationDetails(*, *, *, *, *, *)(*)
    }

    object SendChangeOfResponsibleIndividual {

      def thenReturnSuccess() = {
        when(aMock.sendChangeOfResponsibleIndividual(*, *, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: String, previousResponsibleIndividual: String, newResponsibleIndividual: String, recipients: Set[String]) =
        verify.sendChangeOfResponsibleIndividual(eqTo(requester), eqTo(applicationName), eqTo(previousResponsibleIndividual), eqTo(newResponsibleIndividual), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendChangeOfResponsibleIndividual(*, *, *, *, *)(*)
    }

    object SendCollaboratorAddedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendCollaboratorAddedNotification(*, *, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, role: Role, applicationName: String, recipients: Set[String]) =
        verify.sendCollaboratorAddedNotification(eqTo(requester), eqTo(role), eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendCollaboratorAddedNotification(*, *, *, *)(*)
    }

    object SendCollaboratorAddedConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendCollaboratorAddedConfirmation(*, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(role: Role, applicationName: String, recipients: Set[String]) =
        verify.sendCollaboratorAddedConfirmation(eqTo(role), eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendCollaboratorAddedConfirmation(*, *, *)(*)
    }

    object SendCollaboratorRemovedNotification {

      def thenReturnSuccess() = {
        when(aMock.sendRemovedCollaboratorNotification(*, *, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: String, recipients: Set[String]) =
        verify.sendRemovedCollaboratorNotification(eqTo(requester), eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedCollaboratorNotification(*, *, *)(*)
    }

    object SendCollaboratorRemovedConfirmation {

      def thenReturnSuccess() = {
        when(aMock.sendRemovedCollaboratorConfirmation(*, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def verifyCalledWith(requester: String, applicationName: String, recipients: Set[String]) =
        verify.sendRemovedCollaboratorConfirmation(eqTo(applicationName), eqTo(recipients))(*)

      def verifyNeverCalled() = EmailConnectorMock.verify(never).sendRemovedCollaboratorConfirmation(*, *)(*)
    }

  }
}
