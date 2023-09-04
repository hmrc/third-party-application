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

package uk.gov.hmrc.thirdpartyapplication.mocks.services

import scala.concurrent.Future.successful

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, TermsOfUseInvitation}
import uk.gov.hmrc.thirdpartyapplication.services.TermsOfUseInvitationService

trait TermsOfUseInvitationServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseTermsOfUseInvitationServiceMock {
    def aMock: TermsOfUseInvitationService

    object CreateInvitation {
      def thenReturnSuccess(invite: TermsOfUseInvitation) = when(aMock.createInvitation(*[ApplicationData])(*)).thenAnswer(successful(Some(invite)))
      def thenFail()                                      = when(aMock.createInvitation(*[ApplicationData])(*)).thenAnswer(successful(None))
    }

    object FetchInvitation {
      def thenReturn(invitation: TermsOfUseInvitationResponse) = when(aMock.fetchInvitation(*[ApplicationId])).thenAnswer(successful(Some(invitation)))
      def thenReturnNone                                       = when(aMock.fetchInvitation(*[ApplicationId])).thenAnswer(successful(None))
    }

    object FetchInvitations {
      def thenReturn(invitations: List[TermsOfUseInvitationResponse]) = when(aMock.fetchInvitations()).thenAnswer(successful(invitations))
    }

    object UpdateStatus {
      def thenReturn()                                                                      = when(aMock.updateStatus(*[ApplicationId], *)).thenAnswer(successful(HasSucceeded))
      def verifyCalledWith(applicationId: ApplicationId, status: TermsOfUseInvitationState) = verify(aMock).updateStatus(eqTo(applicationId), eqTo(status))
      def verifyNeverCalled()                                                               = verify(aMock, never).updateStatus(*[ApplicationId], *)
    }

    object UpdateResetBackToEmailSent {
      def thenReturn()                                   = when(aMock.updateResetBackToEmailSent(*[ApplicationId])).thenAnswer(successful(HasSucceeded))
      def verifyCalledWith(applicationId: ApplicationId) = verify(aMock).updateResetBackToEmailSent(eqTo(applicationId))
    }
  }

  object TermsOfUseInvitationServiceMock extends BaseTermsOfUseInvitationServiceMock {
    val aMock = mock[TermsOfUseInvitationService]
  }
}
