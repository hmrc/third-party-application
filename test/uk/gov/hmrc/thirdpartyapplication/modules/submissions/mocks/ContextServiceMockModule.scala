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
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.services.ContextService
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.Context
import uk.gov.hmrc.modules.common.services.EitherTHelper

trait ContextServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with EitherTHelper[String] {
  implicit val ec = scala.concurrent.ExecutionContext.Implicits.global
  
  protected trait BaseContextServiceMock {
    def aMock: ContextService
    
    object DeriveContext {
      def willReturn(context: Context) =
        when(aMock.deriveContext(*[ApplicationId])).thenReturn(pure(context))
        
      def willNotFindApp() =
        when(aMock.deriveContext(*[ApplicationId])).thenReturn(fromEither(Left("Bang")))
    }
  }

  object ContextServiceMock extends BaseContextServiceMock {
    val aMock = mock[ContextService]
  }
}