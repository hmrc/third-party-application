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

package uk.gov.hmrc.apiplatform.modules.subscriptionfields.mocks

import scala.concurrent.Future.{failed, successful}

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

trait ApiSubscriptionFieldsConnectorMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApiSubscriptionFieldsConnectorMock {
    def aMock: ApiSubscriptionFieldsConnector

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object DeleteSubscriptions {

      def thenReturnHasSucceeded() = {
        when(aMock.delete(*[ClientId])(*)).thenReturn(successful(HasSucceeded))
      }

      def thenReturnHasSucceededWhen(clientId: ClientId) = {
        when(aMock.delete(eqTo(clientId))(*)).thenReturn(successful(HasSucceeded))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.delete(*[ClientId])(*)).thenReturn(failed(failsWith))
      }

      def verifyCalledWith(clientId: ClientId) = {
        ApiSubscriptionFieldsConnectorMock.verify.delete(eqTo(clientId))(*)
      }

      def verifyCalled() = {
        ApiSubscriptionFieldsConnectorMock.verify.delete(*[ClientId])(*)
      }
    }

    object BulkFetchFieldDefinitions {

      def willReturnDefinitions(defns: ApiFieldMap[FieldDefinition]) = {
        when(aMock.fetchAllFieldDefinitions()(*)).thenReturn(successful(defns))
      }
    }

    object FetchFieldValues {

      def willReturnFields(values: ApiFieldMap[FieldValue]) = {
        when(aMock.fetchFieldValues(*[ClientId])(*)).thenReturn(successful(values))
      }
    }
  }

  object ApiSubscriptionFieldsConnectorMock extends BaseApiSubscriptionFieldsConnectorMock {
    val aMock = mock[ApiSubscriptionFieldsConnector]
  }
}
