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

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.connector.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

import scala.concurrent.Future.{failed, successful}

trait ApiSubscriptionFieldsConnectorMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApiSubscriptionFieldsConnectorMock {
    def aMock: ApiSubscriptionFieldsConnector

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object DeleteSubscriptions {
      def thenReturnHasSucceeded() = {
        when(aMock.deleteSubscriptions(*)(*)).thenReturn(successful(HasSucceeded))
      }

      def thenReturnHasSucceededWhen(clientId: String) = {
        when(aMock.deleteSubscriptions(eqTo(clientId))(*)).thenReturn(successful(HasSucceeded))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.deleteSubscriptions(*)(*)).thenReturn(failed(failsWith))
      }

      def verifyCalledWith(clientId: String) = {
        ApiSubscriptionFieldsConnectorMock.verify.deleteSubscriptions(eqTo(clientId))(*)
      }

      def verifyCalled() = {
        ApiSubscriptionFieldsConnectorMock.verify.deleteSubscriptions(*)(*)
      }

    }
  }

  object ApiSubscriptionFieldsConnectorMock extends BaseApiSubscriptionFieldsConnectorMock {
    val aMock = mock[ApiSubscriptionFieldsConnector]
  }
}