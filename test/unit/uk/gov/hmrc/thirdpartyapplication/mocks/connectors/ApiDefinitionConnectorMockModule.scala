/*
 * Copyright 2020 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.mocks.connectors

import java.util.UUID

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.connector.ApiDefinitionConnector
import uk.gov.hmrc.thirdpartyapplication.models.ApiDefinition

import scala.concurrent.Future.{failed, successful}

trait ApiDefinitionConnectorMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApiDefinitionConnectorMock {
    def aMock: ApiDefinitionConnector

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object FetchAllAPIs {
      def thenReturn(defs: ApiDefinition*) = {
        when(aMock.fetchAllAPIs(*)(*,*)).thenReturn(successful(defs.toList))
      }

      def thenReturnEmpty(defs: ApiDefinition*) = {
        when(aMock.fetchAllAPIs(*)(*,*)).thenReturn(successful(List.empty))
      }

      def thenReturnWhen(id: UUID)(defs: ApiDefinition*) = {
        when(aMock.fetchAllAPIs(eqTo(id))(*,*)).thenReturn(successful(defs.toList))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.fetchAllAPIs(*)(*,*)).thenReturn(failed(failsWith))
      }

      def verifyCalledWith(id: UUID) = {
        ApiDefinitionConnectorMock.verify.fetchAllAPIs(eqTo(id))(*,*)
      }

      def verifyCalled() = {
        ApiDefinitionConnectorMock.verify.fetchAllAPIs(*)(*,*)
      }

    }
  }

  object ApiDefinitionConnectorMock extends BaseApiDefinitionConnectorMock {
    val aMock = mock[ApiDefinitionConnector]
  }
}
