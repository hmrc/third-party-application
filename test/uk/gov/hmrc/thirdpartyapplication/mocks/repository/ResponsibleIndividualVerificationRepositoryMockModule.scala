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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.ResponsibleIndividualVerificationState
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.Future.successful

trait ResponsibleIndividualVerificationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseResponsibleIndividualVerificationRepositoryMock {
    def aMock: ResponsibleIndividualVerificationRepository

    object FetchByTypeStateAndAge {

      def thenReturn(verifications: ResponsibleIndividualVerification*) =
        when(aMock.fetchByTypeStateAndAge(*, *[ResponsibleIndividualVerificationState], *[LocalDateTime])).thenReturn(Future.successful(List(verifications: _*)))

      def verifyCalledWith(verificationType: String, state: ResponsibleIndividualVerificationState, dateTime: LocalDateTime) =
        verify(aMock).fetchByTypeStateAndAge(verificationType, state, dateTime)
    }

    object DeleteById {
      def thenReturnSuccess()                                            = when(aMock.delete(*[ResponsibleIndividualVerificationId])).thenReturn(successful(HasSucceeded))
      def verifyCalledWith(id: ResponsibleIndividualVerificationId)      = verify(aMock).delete(id)
      def verifyNeverCalledWith(id: ResponsibleIndividualVerificationId) = verify(aMock, never).delete(id)
    }

    object DeleteBySubmission {
      def thenReturnSuccess()                           = when(aMock.delete(*[Submission])).thenReturn(successful(HasSucceeded))
      def verifyCalledWith(submission: Submission)      = verify(aMock).delete(submission)
      def verifyNeverCalledWith(submission: Submission) = verify(aMock, never).delete(submission)
    }

    object UpdateState {
      def thenReturnSuccess()                                                                                      = when(aMock.updateState(*[ResponsibleIndividualVerificationId], *[ResponsibleIndividualVerificationState])).thenReturn(successful(HasSucceeded))
      def verifyCalledWith(id: ResponsibleIndividualVerificationId, state: ResponsibleIndividualVerificationState) = verify(aMock).updateState(id, state)
    }

    object Save {
      def thenReturnSuccess()                                               = when(aMock.save(*[ResponsibleIndividualVerification])).thenAnswer((riv: ResponsibleIndividualVerification) => successful(riv))
      def verifyCalledWith(verification: ResponsibleIndividualVerification) = verify(aMock).save(verification)
    }

    object Fetch {
      def thenReturn(verification: ResponsibleIndividualVerification) = when(aMock.fetch(verification.id)).thenReturn(successful(Some(verification)))
      def verifyCalledWith(id: ResponsibleIndividualVerificationId)   = verify(aMock).fetch(id)
      def thenReturnNothing                                           = when(aMock.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(successful(None))
    }

    object UpdateSetDefaultVerificationType {
      def thenReturnSuccess()                   = when(aMock.updateSetDefaultVerificationType(*)).thenReturn(successful(HasSucceeded))
      def verifyCalledWith(defaultType: String) = verify(aMock).updateSetDefaultVerificationType(defaultType)
    }

    object ApplyEvents {
      def succeeds() = {
        when(aMock.applyEvents(*)).thenReturn(Future.successful(HasSucceeded))
      }
    }

  }

  object ResponsibleIndividualVerificationRepositoryMock extends BaseResponsibleIndividualVerificationRepositoryMock {
    val aMock = mock[ResponsibleIndividualVerificationRepository]
  }
}
