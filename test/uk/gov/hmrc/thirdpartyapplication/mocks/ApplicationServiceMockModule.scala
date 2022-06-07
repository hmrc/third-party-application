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

import cats.data.OptionT
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, TermsOfUseAcceptance}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.concurrent.ExecutionContext.Implicits.global
import cats.implicits.catsStdInstancesForFuture
import org.mockito.captor.ArgCaptor
import cats.data.OptionT
import scala.concurrent.Future
import cats.implicits._

trait ApplicationServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {
  protected trait BaseApplicationServiceMock {

    def aMock: ApplicationService

    object Fetch {
      def thenReturn(applicationData: ApplicationData) = {
        val r: OptionT[Future,ApplicationResponse] = OptionT.pure[Future](ApplicationResponse(data=applicationData))
        when(aMock.fetch(*[ApplicationId])).thenReturn(r)
      }
      def thenReturnNothing() = when(aMock.fetch(*[ApplicationId])).thenReturn(OptionT.fromOption[Future](None))
    }

    object AddTermsOfUseAcceptance {
      def thenReturn(applicationData: ApplicationData) =
        when(aMock.addTermsOfUseAcceptance(*[ApplicationId], *[TermsOfUseAcceptance])).thenReturn(successful(applicationData))

      def verifyCalledWith(applicationId: ApplicationId) = {
        val captor = ArgCaptor[TermsOfUseAcceptance]
        verify(aMock).addTermsOfUseAcceptance(eqTo(applicationId), captor.capture)
        captor.value
      }

      def verifyNeverCalled() = verify(aMock, never).addTermsOfUseAcceptance(*[ApplicationId], *[TermsOfUseAcceptance])
    }
  }
  
  object ApplicationServiceMock extends BaseApplicationServiceMock {
    val aMock = mock[ApplicationService]
  }
}
