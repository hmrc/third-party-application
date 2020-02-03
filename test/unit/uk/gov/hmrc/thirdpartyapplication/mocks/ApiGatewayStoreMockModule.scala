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

package unit.uk.gov.hmrc.thirdpartyapplication.mocks

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, Mockito, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.models
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{RateLimitTier, SILVER}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{APIIdentifier, EnvironmentToken, HasSucceeded, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.services.ApiGatewayStore

import scala.concurrent.Future.{failed, successful}

trait ApiGatewayStoreMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApiGatewayStoreMock {
    def aMock: ApiGatewayStore

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object CreateApplication {
      def thenReturn(environmentToken: EnvironmentToken) = {
        when(aMock.createApplication(*,*,*)(*)).thenReturn(successful(environmentToken))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.createApplication(*,*,*)(*)).thenReturn(failed(failsWith))
      }

      def verifyCalled() = {
        ApiGatewayStoreMock.verify.createApplication(*,*,*)(*)
      }
    }

    object UpdateApplication {
      def thenReturnHasSucceeded() = {
        when(aMock.updateApplication(*,*)(*)).thenReturn(successful(HasSucceeded))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.updateApplication(*,*)(*)).thenReturn(failed(failsWith))
      }

      def verifyCalledWith(applicationData: ApplicationData, tier: RateLimitTier.Value) =
        ApiGatewayStoreMock.verify.updateApplication(eqTo(applicationData),refEq(tier))(*)

      def verifyCalled() =
        ApiGatewayStoreMock.verify.updateApplication(*,*)(*)

      def verifyNeverCalled() =
        ApiGatewayStoreMock.verify(never).updateApplication(*,*)(*)

    }

    object DeleteApplication {
      def verifyCalledWith(application: ApplicationData) = {
        import application.{wso2ApplicationName,wso2Username,wso2Password}
        ApiGatewayStoreMock.verify.deleteApplication(eqTo(wso2Username), eqTo(wso2Password), eqTo(wso2ApplicationName))(*)
      }

      def thenReturnHasSucceeded() = {
        when(aMock.deleteApplication(*,*,*)(*)).thenReturn(successful(HasSucceeded))
      }

      def thenFail(failsWith: Throwable) = {
        when(aMock.deleteApplication(*,*,*)(*)).thenReturn(failed(failsWith))
      }

      def verifyCalled() = {
        ApiGatewayStoreMock.verify.deleteApplication(*,*,*)(*)
      }

    }

    object GetSubscriptions {
      def thenReturn(originalApplicationData: ApplicationData)(subs: List[APIIdentifier]) = {
        val oad = originalApplicationData
        when(aMock.getSubscriptions(eqTo(oad.wso2ApplicationName),eqTo(oad.wso2Username),eqTo(oad.wso2Password))(*)).thenReturn(successful(subs))
      }

      def thenReturn(subs: List[APIIdentifier]) =
        when(aMock.getSubscriptions(*,*,*)(*)).thenReturn(successful(subs))
    }

    object RemoveSubscription {
      def thenReturnHasSucceeded() =
        when(aMock.removeSubscription(*,*)(*)).thenReturn(successful(HasSucceeded))

      def thenReturnHasSucceededWhen(application: ApplicationData, apiId: APIIdentifier) =
        when(aMock.removeSubscription(eqTo(application),eqTo(apiId))(*)).thenReturn(successful(HasSucceeded))

      def verifyCalledwith(application: ApplicationData, apiId: APIIdentifier) =
        ApiGatewayStoreMock.verify.removeSubscription(eqTo(application), eqTo(apiId))(*)
    }

    object ResubscribeApi {
      def thenReturnHasSucceededWhen(apiIdentifier: APIIdentifier) =
        when(aMock.resubscribeApi(*,*,*,*,eqTo(apiIdentifier),*)(*)).thenReturn(successful(HasSucceeded))

      def thenFailWhen(apiIdentifier: APIIdentifier)(failWith: Throwable) =
        when(aMock.resubscribeApi(*,*,*,*,eqTo(apiIdentifier),*)(*)).thenReturn(failed(failWith))

      def verifyNeverCalled() = ApiGatewayStoreMock.verify(never).resubscribeApi(*,*,*,*,*,*)(*)

      def verifyCalled() = ApiGatewayStoreMock.verify.resubscribeApi(*,*,*,*,*,*)(*)

      def verifyCalledWith(
                            identifiers: List[APIIdentifier],
                            originalApplicationData: ApplicationData,
                            apiIdentifier: APIIdentifier,
                            rateLimitTier: RateLimitTier) = {
        import originalApplicationData.{wso2Password,wso2Username,wso2ApplicationName}
        ApiGatewayStoreMock.verify.resubscribeApi(
            eqTo(identifiers),
            eqTo(wso2Username),
            eqTo(wso2Password),
            eqTo(wso2ApplicationName),
            eqTo(apiIdentifier),
            eqTo(rateLimitTier))(*)
      }

      def thenReturnHasSucceeded() = {
        when(aMock.resubscribeApi(*,*,*,*,*,*)(*)).thenReturn(successful(HasSucceeded))
      }

    }

    object CheckRateLimitTier {
      def thenReturnHasSucceededWhen(originalApplicationData: ApplicationData, expected: RateLimitTier) = {
        val oad = originalApplicationData
        when(aMock.checkApplicationRateLimitTier(
          eqTo(oad.wso2ApplicationName),
          eqTo(oad.wso2Username),
          eqTo(oad.wso2Password),
          eqTo(expected))(*)).thenReturn(successful(HasSucceeded))
      }

      def thenReturnHasSucceeded() = {
        when(aMock.checkApplicationRateLimitTier(*,*,*,*)(*)).thenReturn(successful(HasSucceeded))
      }

    }
  }

  object ApiGatewayStoreMock extends BaseApiGatewayStoreMock {
    val aMock = mock[ApiGatewayStore](withSettings.lenient())


  }
}
