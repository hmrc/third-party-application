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

import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse
import uk.gov.hmrc.thirdpartyapplication.services.TermsOfUseInvitationService

trait TermsOfUseServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseTermsOfUseServiceMock {
    def aMock: TermsOfUseInvitationService

    object CreateInvitations {
      def thenReturnSuccess() = when(aMock.createInvitation(*[ApplicationId])).thenAnswer(successful(true))
      def thenFail()          = when(aMock.createInvitation(*[ApplicationId])).thenAnswer(successful(false))
    }

    object FetchInvitation {
      def thenReturn(invitation: TermsOfUseInvitationResponse) = when(aMock.fetchInvitation(*[ApplicationId])).thenAnswer(successful(Some(invitation)))
      def thenReturnNone                                       = when(aMock.fetchInvitation(*[ApplicationId])).thenAnswer(successful(None))
    }

    object FetchInvitations {
      def thenReturn(invitations: List[TermsOfUseInvitationResponse]) = when(aMock.fetchInvitations()).thenAnswer(successful(invitations))
    }
  }

  object TermsOfUseServiceMock extends BaseTermsOfUseServiceMock {
    val aMock = mock[TermsOfUseInvitationService]
  }
}
