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

package uk.gov.hmrc.apiplatform.modules.approvals.mocks

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.apiplatform.modules.approvals.services.GrantWithWarningsApprovalsService

trait GrantWithWarningsApprovalsServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {
  protected trait BaseGrantWithWarningsApprovalsServiceMock {
    def aMock: GrantWithWarningsApprovalsService

    object Grant {
      def thenReturn(result: GrantWithWarningsApprovalsService.Result) = when(aMock.grantWithWarnings(*, *, *, *)(*)).thenReturn(successful(result))
    }
  }
  
  object GrantWithWarningsApprovalsServiceMock extends BaseGrantWithWarningsApprovalsServiceMock {
    val aMock = mock[GrantWithWarningsApprovalsService]
  }
}
