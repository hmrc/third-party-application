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

package uk.gov.hmrc.thirdpartyapplication.mocks

import org.mockito.{ArgumentMatchersSugar, MockitoSugar, Strictness}

import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService

trait ApplicationNameValidationConfigMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApplicationNameValidationConfigMock {
    def aMock: ApplicationNamingService.Config

    object NameDenyList {

      def thenReturns(denyListedNames: List[String]) =
        when(aMock.nameDenyList).thenReturn(denyListedNames)

      def thenReturnsAnEmptyList() =
        thenReturns(List.empty[String])

    }

    object ValidateForDuplicateAppNames {

      def thenReturns(validateForDuplicates: Boolean) =
        when(aMock.validateForDuplicateAppNames).thenReturn(validateForDuplicates)
    }
  }

  object ApplicationNameValidationConfigMock extends BaseApplicationNameValidationConfigMock {
    val aMock = mock[ApplicationNamingService.Config](withSettings.strictness(Strictness.Lenient))
  }
}
