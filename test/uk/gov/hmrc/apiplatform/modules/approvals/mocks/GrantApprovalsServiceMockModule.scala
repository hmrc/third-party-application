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

package uk.gov.hmrc.apiplatform.modules.approvals.mocks

import scala.concurrent.Future.successful

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.approvals.services.GrantApprovalsService
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

trait GrantApprovalsServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {

  protected trait BaseGrantApprovalsServiceMock {
    def aMock: GrantApprovalsService

    object DeclineForTouUplift {
      def thenReturn(result: GrantApprovalsService.Result) = when(aMock.declineForTouUplift(*, *, *, *)).thenReturn(successful(result))
    }

    object GrantWithWarningsForTouUplift {
      def thenReturn(result: GrantApprovalsService.Result) = when(aMock.grantWithWarningsForTouUplift(*, *, *, *)).thenReturn(successful(result))
    }

    object ResetForTouUplift {
      def thenReturn(result: GrantApprovalsService.Result) = when(aMock.resetForTouUplift(*, *, *, *)).thenReturn(successful(result))
    }

    object DeleteTouUplift {
      def thenReturn(result: GrantApprovalsService.Result) = when(aMock.deleteTouUplift(*, *, *)).thenReturn(successful(result))
    }
  }

  object GrantApprovalsServiceMock extends BaseGrantApprovalsServiceMock {
    val aMock = mock[GrantApprovalsService]
  }
}
