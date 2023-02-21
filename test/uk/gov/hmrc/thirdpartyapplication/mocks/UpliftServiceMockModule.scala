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

import scala.concurrent.Future.{failed, successful}

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.uplift.domain.models.InvalidUpliftVerificationCode
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, UpliftVerified}

trait UpliftServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseUpliftServiceMock {
    def aMock: UpliftService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object RequestUplift {

      def thenReturnWhen(appId: ApplicationId, name: String, email: LaxEmailAddress)(value: ApplicationStateChange) =
        when(aMock.requestUplift(eqTo(appId), eqTo(name), eqTo(email))(*)).thenReturn(successful(value))

      def thenReturn(value: ApplicationStateChange) = {
        when(aMock.requestUplift(*[ApplicationId], *, *[LaxEmailAddress])(*)).thenReturn(successful(value))
      }

      def thenFailsWith(ex: Exception) =
        when(aMock.requestUplift(*[ApplicationId], *, *[LaxEmailAddress])(*)).thenReturn(failed(ex))
    }

    object VerifyUplift {

      def thenSucceeds() = {
        when(aMock.verifyUplift(*)(*)).thenReturn(successful(UpliftVerified))
      }

      def thenFailWithInvalidUpliftVerificationCode() = {
        when(aMock.verifyUplift(*)(*)).thenAnswer((code: String) => failed(InvalidUpliftVerificationCode(code)))
      }
    }
  }

  object UpliftServiceMock extends BaseUpliftServiceMock {
    val aMock = mock[UpliftService](withSettings.lenient())
  }
}
