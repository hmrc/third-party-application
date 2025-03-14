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

package uk.gov.hmrc.apiplatform.modules.submissions.mocks

import scala.concurrent.Future.successful

import org.mockito.captor.{ArgCaptor, Captor}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService

trait SubmissionsServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseSubmissionsServiceMock {
    def aMock: SubmissionsService

    def verify = MockitoSugar.verify(aMock)

    object Create {

      def thenReturn(submission: Submission) =
        when(aMock.create(*[ApplicationId], *)).thenReturn(successful(Right(submission)))

      def thenFails(error: String) =
        when(aMock.create(*[ApplicationId], *)).thenReturn(successful(Left(error)))
    }

    object FetchLatest {

      def thenReturn(submission: Submission) =
        when(aMock.fetchLatest(*[ApplicationId])).thenReturn(successful(Some(submission)))

      def thenReturnWhen(submission: Submission) =
        when(aMock.fetchLatest(eqTo(submission.applicationId))).thenReturn(successful(Some(submission)))

      def thenReturnNone() =
        when(aMock.fetchLatest(*[ApplicationId])).thenReturn(successful(None))

      def thenReturnNoneWhen(applicationId: ApplicationId) =
        when(aMock.fetchLatest(eqTo(applicationId))).thenReturn(successful(None))
    }

    object FetchLatestExtended {

      def thenReturn(extSubmission: ExtendedSubmission) =
        when(aMock.fetchLatestExtended(*[ApplicationId])).thenReturn(successful(Some(extSubmission)))

      def thenReturnNone() =
        when(aMock.fetchLatestExtended(*[ApplicationId])).thenReturn(successful(None))
    }

    object FetchLatestMarkedSubmission {

      def thenReturn(markedSubmission: MarkedSubmission) =
        when(aMock.fetchLatestMarkedSubmission(*[ApplicationId])).thenReturn(successful(Right(markedSubmission)))

      def thenFails(error: String) =
        when(aMock.fetchLatestMarkedSubmission(*[ApplicationId])).thenReturn(successful(Left(error)))
    }

    object Fetch {

      def thenReturn(extSubmission: ExtendedSubmission) =
        when(aMock.fetch(*[SubmissionId])).thenReturn(successful(Some(extSubmission)))

      def thenReturnNone() =
        when(aMock.fetch(*[SubmissionId])).thenReturn(successful(None))
    }

    object RecordAnswers {

      def thenReturn(extSubmission: ExtendedSubmission) =
        when(aMock.recordAnswers(*[SubmissionId], *[Question.Id], *[List[String]])).thenReturn(successful(Right(extSubmission)))

      def thenFails(error: String) =
        when(aMock.recordAnswers(*[SubmissionId], *[Question.Id], *[List[String]])).thenReturn(successful(Left(error)))
    }

    object DeleteAll {

      def thenReturn() =
        when(aMock.deleteAllAnswersForApplication(*[ApplicationId])).thenReturn(successful(1))

      def verifyCalledWith(applicationId: ApplicationId) = {
        SubmissionsServiceMock.verify.deleteAllAnswersForApplication(applicationId)
      }
    }

    object Store {

      def thenReturnWith(s: Submission) =
        when(aMock.store(*[Submission])).thenReturn(successful(s))

      def thenReturn() =
        when(aMock.store(*[Submission])).thenAnswer((s: Submission) => (successful(s)))

      def verifyCalledWith() = {
        val capture: Captor[Submission] = ArgCaptor[Submission]
        SubmissionsServiceMock.verify.store(capture)
        capture.value
      }
    }

    object DeclineApprovalRequest {

      def succeeds() = {
        when(aMock.declineApplicationApprovalRequest(*)).thenReturn(successful(None))
      }

      def succeedsWith(submission: Submission) = {
        when(aMock.declineApplicationApprovalRequest(*)).thenReturn(successful(Some(submission)))
      }
    }

    object MarkSubmission {

      def thenReturn(submission: Submission) =
        when(aMock.markSubmission(*[ApplicationId], *)).thenReturn(successful(Some(submission)))
    }

    object DeclineSubmission {

      def thenReturn(submission: Submission) =
        when(aMock.declineSubmission(*[ApplicationId], *, *)).thenReturn(successful(Some(submission)))
    }
  }

  object SubmissionsServiceMock extends BaseSubmissionsServiceMock {
    val aMock = mock[SubmissionsService]
  }
}
