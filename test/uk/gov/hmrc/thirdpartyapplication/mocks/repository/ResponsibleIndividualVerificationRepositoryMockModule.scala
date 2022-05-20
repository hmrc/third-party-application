/*
 * Copyright 2022 HM Revenue & Customs
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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.ResponsibleIndividualVerificationState
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.Future.successful

trait ResponsibleIndividualVerificationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseResponsibleIndividualVerificationRepositoryMock {
    def aMock: ResponsibleIndividualVerificationRepository

    object FetchByStateAndAge {
      def thenReturn(verifications: ResponsibleIndividualVerification*) =
        when(aMock.fetchByStateAndAge(*[ResponsibleIndividualVerificationState], *[LocalDateTime])).thenReturn(Future.successful(List(verifications:_*)))
      def verifyCalledWith(state: ResponsibleIndividualVerificationState, dateTime: LocalDateTime) =
        verify(aMock).fetchByStateAndAge(state, dateTime)
    }

    object Delete {
      def thenReturnSuccess() = when(aMock.delete(*[ResponsibleIndividualVerificationId])).thenReturn(successful(HasSucceeded))
      def verifyCalledWith(id: ResponsibleIndividualVerificationId) = verify(aMock).delete(id)
    }

    object UpdateState {
      def thenReturnSuccess() = when(aMock.updateState(*[ResponsibleIndividualVerificationId], *[ResponsibleIndividualVerificationState])).thenReturn(successful(HasSucceeded))
      def verifyCalledWith(id: ResponsibleIndividualVerificationId, state: ResponsibleIndividualVerificationState) = verify(aMock).updateState(id, state)
    }
  }

  object ResponsibleIndividualVerificationRepositoryMock extends BaseResponsibleIndividualVerificationRepositoryMock {
    val aMock = mock[ResponsibleIndividualVerificationRepository]
  }
}