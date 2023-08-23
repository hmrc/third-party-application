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

package uk.gov.hmrc.thirdpartyapplication.mocks.repository

import java.time.Instant
import scala.concurrent.Future.successful

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.repository.TermsOfUseInvitationRepository
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.TermsOfUseInvitationState

trait TermsOfUseInvitationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseTermsOfUseInvitationRepositoryMock {
    def aMock: TermsOfUseInvitationRepository

    object Create {
      def thenReturnSuccess(invite: TermsOfUseInvitation) = when(aMock.create(*[TermsOfUseInvitation])).thenAnswer(successful(Some(invite)))
      def thenReturnFailure()                             = when(aMock.create(*[TermsOfUseInvitation])).thenAnswer(successful(None))
    }

    object FetchInvitation {
      def thenReturn(invite: TermsOfUseInvitation) = when(aMock.fetch(*[ApplicationId])).thenAnswer(successful(Some(invite)))
      def thenReturnNone()                         = when(aMock.fetch(*[ApplicationId])).thenAnswer(successful(None))
    }

    object FetchAll {
      def thenReturn(invitations: List[TermsOfUseInvitation]) = when(aMock.fetchAll()).thenAnswer(successful(invitations))
    }

    object FetchByStatusBeforeDueBy {
      def thenReturn(invitations: List[TermsOfUseInvitation])                 = when(aMock.fetchByStatusBeforeDueBy(*, *)).thenAnswer(successful(invitations))
      def verifyCalledWith(status: TermsOfUseInvitationState, dueBy: Instant) = verify(aMock).fetchByStatusBeforeDueBy(eqTo(status), eqTo(dueBy))
    }

    object UpdateState {
      def thenReturn() = when(aMock.updateState(*[ApplicationId], *)).thenAnswer(successful(HasSucceeded))
    }

    object UpdateReminderSent {
      def thenReturn()                                        = when(aMock.updateReminderSent(*[ApplicationId])).thenAnswer(successful(HasSucceeded))
      def verifyCalledWith(applicationId: ApplicationId)      = verify(aMock).updateReminderSent(eqTo(applicationId))
      def verifyNeverCalled()                                 = verify(aMock, never).updateReminderSent(*[ApplicationId])
    }

    object Delete {
      def thenReturn() = when(aMock.delete(*[ApplicationId])).thenAnswer(successful(HasSucceeded))
    }
  }

  object TermsOfUseInvitationRepositoryMock extends BaseTermsOfUseInvitationRepositoryMock {
    val aMock = mock[TermsOfUseInvitationRepository]
  }
}
