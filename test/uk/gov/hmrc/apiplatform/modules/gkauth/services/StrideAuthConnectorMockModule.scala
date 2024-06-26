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

package uk.gov.hmrc.apiplatform.modules.gkauth.services

import scala.concurrent.Future.{failed, successful}

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.auth.core.retrieve.Name

import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector

trait StrideAuthConnectorMockModule {
  self: MockitoSugar with ArgumentMatchersSugar =>

  protected trait BaseStrideAuthConnectorMock {
    def aMock: StrideAuthConnector

    object Authorise {
      def succeeds = when(aMock.authorise[Unit](*, *)(*, *)).thenReturn(successful(()))

      def succeedsAndReturnsName(name: String) = when(aMock.authorise[Option[Name]](*, *)(*, *)).thenReturn(successful((Some(Name(Some(name), Some("Example"))))))

      def fails = when(aMock.authorise[Unit](*, *)(*, *)).thenReturn(failed(new RuntimeException))

      def verifyNotCalled = verify(aMock, never).authorise[Unit](*, *)(*, *)

      def verifyCalledOnce = verify(aMock, times(1)).authorise[Unit](*, *)(*, *)
    }
  }

  object StrideAuthConnectorMock extends BaseStrideAuthConnectorMock {
    val aMock = mock[StrideAuthConnector]
  }
}
