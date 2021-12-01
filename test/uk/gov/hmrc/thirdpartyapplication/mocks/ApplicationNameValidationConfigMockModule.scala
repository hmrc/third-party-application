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

package uk.gov.hmrc.thirdpartyapplication.mocks

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNameValidationConfig

trait ApplicationNameValidationConfigMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseApplicationNameValidationConfigMock {
    def aMock: ApplicationNameValidationConfig

    object NameBlackList {
      def thenReturns(blacklistedNames: List[String]) = 
        when(aMock.nameBlackList).thenReturn(blacklistedNames)
      
      def thenReturnsAnEmptyList() = 
        thenReturns(List.empty[String])
      
    }

    object ValidateForDuplicateAppNames {
      def thenReturns(validateForDuplicates: Boolean) = 
        when(aMock.validateForDuplicateAppNames).thenReturn(validateForDuplicates)
    }
  }
  
  object ApplicationNameValidationConfigMock extends BaseApplicationNameValidationConfigMock {
    val aMock = mock[ApplicationNameValidationConfig](withSettings.lenient())
  }
}