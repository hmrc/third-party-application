/*
 * Copyright 2021 HM Revenue & Customs
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

/*
  * Copyright 2021 HM Revenue & Customs
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

  import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

  import scala.concurrent.Future.successful
  import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories.SubmissionsDAO
  import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

  trait SubmissionsDAOMockModule extends MockitoSugar with ArgumentMatchersSugar {
    protected trait BaseSubmissionsDAOMock {
      def aMock: SubmissionsDAO

      object Save {
        def thenReturn() =
          when(aMock.save(*[Submission])).thenAnswer( (s: Submission) => (successful(s)) )

        def verifyCalled() = 
          verify(aMock, atLeast(1)).save(*[Submission])
      }

      object Fetch {
        def thenReturn(submission: Submission) =
          when(aMock.fetch(*[SubmissionId])).thenReturn(successful(Some(submission)))
        def thenReturnNothing() =
          when(aMock.fetch(*[SubmissionId])).thenReturn(successful(None))
      }

      object FetchLatest {
        def thenReturn(submission: Submission) =
          when(aMock.fetchLatest(*[ApplicationId])).thenReturn(successful(Some(submission)))
        def thenReturnNothing() =
          when(aMock.fetchLatest(*[ApplicationId])).thenReturn(successful(None))
      }

      object Update {
        def thenReturn() =
          when(aMock.update(*[Submission])).thenAnswer( (s: Submission) => (successful(s)) )
          
        def verifyCalled() = 
          verify(aMock, atLeast(1)).update(*[Submission])
      }
    }

    object SubmissionsDAOMock extends BaseSubmissionsDAOMock {
      val aMock = mock[SubmissionsDAO]
    }
  }
