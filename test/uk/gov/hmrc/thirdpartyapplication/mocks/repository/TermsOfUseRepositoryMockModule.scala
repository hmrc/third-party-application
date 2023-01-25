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
import uk.gov.hmrc.thirdpartyapplication.repository.TermsOfUseRepository
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import scala.concurrent.Future.successful

trait TermsOfUseRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseTermsOfUseRepositoryMock {
    def aMock: TermsOfUseRepository

    object Create {
      def thenReturnSuccess() = when(aMock.create(*[TermsOfUseInvitation])).thenAnswer(successful(true))
    }

    object FetchAll {
      def thenReturn(invitations: List[TermsOfUseInvitation]) = when(aMock.fetchAll()).thenAnswer(successful(invitations))
    }
  }

  object TermsOfUseRepositoryMock extends BaseTermsOfUseRepositoryMock {
    val aMock = mock[TermsOfUseRepository]
  }
}
