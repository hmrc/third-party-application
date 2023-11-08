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

import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationAlreadyExists, DuplicateName, InvalidName, ValidName}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService.ExclusionCondition

trait UpliftNamingServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseUpliftNamingServiceMock {
    def aMock: UpliftNamingService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object AssertAppHasUniqueNameAndAudit {

      def thenSucceeds() = {
        when(aMock.assertAppHasUniqueNameAndAudit(*, *, *)(*)).thenReturn(successful(()))
      }

      def thenFailsWithApplicationAlreadyExists() = {
        when(aMock.assertAppHasUniqueNameAndAudit(*, *, *)(*)).thenAnswer((appName: String, _: AccessType, _: Option[ApplicationData]) => failed(ApplicationAlreadyExists(appName)))
      }
    }

    object ValidateApplicationName {
      def succeeds()               = when(aMock.validateApplicationName(*[String], *[ExclusionCondition])).thenReturn(successful(ValidName))
      def failsWithDuplicateName() = when(aMock.validateApplicationName(*, *[ExclusionCondition])).thenReturn(successful(DuplicateName))
      def failsWithInvalidName()   = when(aMock.validateApplicationName(*, *[ExclusionCondition])).thenReturn(successful(InvalidName))
    }
  }

  object UpliftNamingServiceMock extends BaseUpliftNamingServiceMock {
    val aMock = mock[UpliftNamingService](withSettings.lenient())
  }
}
