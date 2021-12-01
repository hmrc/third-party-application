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
import org.mockito.verification.VerificationMode

import scala.concurrent.Future.{successful,failed}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationAlreadyExists
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.AccessType
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.modules.uplift.services.UpliftApplicationNamingService

trait UpliftApplicationNamingServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {
    
  protected trait BaseApplicationNamingServiceMock {
    def aMock: UpliftApplicationNamingService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object AssertAppHasUniqueNameAndAudit {
      def thenSucceeds() = {
        when(aMock.assertAppHasUniqueNameAndAudit(*, *, *)(*)).thenReturn(successful(Unit))
      }
      def thenFailsWithApplicationAlreadyExists() = {
        when(aMock.assertAppHasUniqueNameAndAudit(*, *, *)(*)).thenAnswer( (appName: String, _: AccessType, _: Option[ApplicationData]) => failed(ApplicationAlreadyExists(appName)))
      }
    }
  }
  
  object UpliftApplicationNamingServiceMock extends BaseApplicationNamingServiceMock {
    val aMock = mock[UpliftApplicationNamingService](withSettings.lenient())
  }
}
