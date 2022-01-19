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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.UserId

trait SubmissionsServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseSubmissionsServiceMock {
    def aMock: SubmissionsService

    object Create {
      def thenReturn(extSubmission: ExtendedSubmission) = 
        when(aMock.create(*[ApplicationId], *[UserId])).thenReturn(successful(Right(extSubmission)))

      def thenFails(error: String) =
        when(aMock.create(*[ApplicationId], *[UserId])).thenReturn(successful(Left(error)))
    }

    object FetchLatest {
      def thenReturn(extSubmission: Option[ExtendedSubmission]) =
        when(aMock.fetchLatest(*[ApplicationId])).thenReturn(successful(extSubmission))
    }

    object FetchLatestMarkedSubmission {
      def thenReturn(markedSubmission: MarkedSubmission) =
        when(aMock.fetchLatestMarkedSubmission(*[ApplicationId])).thenReturn(successful(Right(markedSubmission)))

      def thenFails(error: String) =
        when(aMock.fetchLatestMarkedSubmission(*[ApplicationId])).thenReturn(successful(Left(error)))
    }

    object Fetch {
      def thenReturn(extSubmission: Option[ExtendedSubmission]) =
        when(aMock.fetch(*[Submission.Id])).thenReturn(successful(extSubmission))
    }

    object RecordAnswers {
      def thenReturn(extSubmission: ExtendedSubmission) =
        when(aMock.recordAnswers(*[Submission.Id], *[QuestionId], *[List[String]])).thenReturn(successful(Right(extSubmission)))

      def thenFails(error: String) = 
        when(aMock.recordAnswers(*[Submission.Id], *[QuestionId], *[List[String]])).thenReturn(successful(Left(error)))
    }

    object DeleteAll {
      def thenReturn() =
        when(aMock.deleteAllAnswersForApplication(*[ApplicationId])).thenReturn(successful(()))
    }
  }

  object SubmissionsServiceMock extends BaseSubmissionsServiceMock {
    val aMock = mock[SubmissionsService]
  }
}
