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

import scala.concurrent.Future.successful

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.repository.TermsOfUseInvitationRepository

trait TermsOfUseInvitationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseTermsOfUseRepositoryMock {
    def aMock: TermsOfUseInvitationRepository

    object Create {
      def thenReturnSuccess(invite: TermsOfUseInvitation) = when(aMock.create(*[TermsOfUseInvitation])).thenAnswer(successful(Some(invite)))
      def thenReturnFailure() = when(aMock.create(*[TermsOfUseInvitation])).thenAnswer(successful(None))
    }

    object FetchInvitation {
      def thenReturn(invite: TermsOfUseInvitation) = when(aMock.fetch(*[ApplicationId])).thenAnswer(successful(Some(invite)))
      def thenReturnNone()                         = when(aMock.fetch(*[ApplicationId])).thenAnswer(successful(None))
    }

    object FetchAll {
      def thenReturn(invitations: List[TermsOfUseInvitation]) = when(aMock.fetchAll()).thenAnswer(successful(invitations))
    }
  }

  object TermsOfUseRepositoryMock extends BaseTermsOfUseRepositoryMock {
    val aMock = mock[TermsOfUseInvitationRepository]
  }
}
