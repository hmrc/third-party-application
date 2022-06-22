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

package uk.gov.hmrc.thirdpartyapplication.mocks

import cats.data.{EitherT, NonEmptyChain}
import cats.implicits.catsStdInstancesForFuture
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, ApplicationUpdate}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationUpdateService
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

import scala.concurrent.ExecutionContext.Implicits.global

trait ApplicationUpdateServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {
  protected trait BaseApplicationUpdateServiceMock {

    def aMock: ApplicationUpdateService

    object Update {
      def thenReturnSuccess(applicationData: ApplicationData) =
        when(aMock.update(*[ApplicationId], *[ApplicationUpdate])(*)).thenReturn(EitherT.rightT(applicationData))

      def thenReturnError(errorMsg: String) =
        when(aMock.update(*[ApplicationId], *[ApplicationUpdate])(*)).thenReturn(EitherT.leftT(NonEmptyChain(errorMsg)))

      def verifyNeverCalled =
        verify(aMock, never).update(*[ApplicationId], *[ApplicationUpdate])(*)
    }
  }
  
  object ApplicationUpdateServiceMock extends BaseApplicationUpdateServiceMock {
    val aMock = mock[ApplicationUpdateService]
  }
}
