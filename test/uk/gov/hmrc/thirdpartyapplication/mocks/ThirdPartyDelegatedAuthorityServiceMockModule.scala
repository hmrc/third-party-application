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

import scala.concurrent.Future.successful

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.services.ThirdPartyDelegatedAuthorityService

trait ThirdPartyDelegatedAuthorityServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseThirdPartyDelegatedAuthorityServiceMock {
    def aMock: ThirdPartyDelegatedAuthorityService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: org.mockito.verification.VerificationMode) = MockitoSugar.verify(aMock, mode)

    object ApplyEvents {

      def succeeds() = {
        when(aMock.applyEvents(*)(*)).thenReturn(successful(None))
      }
    }
  }

  object ThirdPartyDelegatedAuthorityServiceMock extends BaseThirdPartyDelegatedAuthorityServiceMock {
    val aMock = mock[ThirdPartyDelegatedAuthorityService]
  }
}
