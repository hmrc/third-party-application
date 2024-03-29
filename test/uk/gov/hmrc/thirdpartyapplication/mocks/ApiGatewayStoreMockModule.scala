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
import org.mockito.{ArgumentMatchersSugar, MockitoSugar, Strictness}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.ApiGatewayStore

trait ApiGatewayStoreMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApiGatewayStoreMock {
    def aMock: ApiGatewayStore

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object CreateApplication {

      def thenReturnHasSucceeded() = {
        when(aMock.createApplication(*, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.createApplication(*, *)(*)).thenReturn(failed(failsWith))
      }

      def verifyCalled() = {
        verify.createApplication(*, *)(*)
      }

      def verifyNeverCalled() = {
        verify(never).createApplication(*, *)(*)
      }
    }

    object UpdateApplication {

      def thenReturnHasSucceeded() = {
        when(aMock.updateApplication(*, *)(*)).thenReturn(successful(HasSucceeded))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.updateApplication(*, *)(*)).thenReturn(failed(failsWith))
      }

      def verifyCalledWith(applicationData: StoredApplication, tier: RateLimitTier) =
        verify.updateApplication(eqTo(applicationData), refEq(tier))(*)

      def verifyCalled() =
        verify.updateApplication(*, *)(*)

      def verifyNeverCalled() =
        verify(never).updateApplication(*, *)(*)

    }

    object DeleteApplication {

      def verifyCalledWith(application: StoredApplication) = {
        import application.wso2ApplicationName
        ApiGatewayStoreMock.verify.deleteApplication(eqTo(wso2ApplicationName))(*)
      }

      def thenReturnHasSucceeded() = {
        when(aMock.deleteApplication(*)(*)).thenReturn(successful(HasSucceeded))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.deleteApplication(*)(*)).thenReturn(failed(failsWith))
      }

      def verifyCalled() = {
        verify.deleteApplication(*)(*)
      }

    }
  }

  object ApiGatewayStoreMock extends BaseApiGatewayStoreMock {
    val aMock = mock[ApiGatewayStore](withSettings.strictness(Strictness.Lenient))
  }
}
