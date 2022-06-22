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

package uk.gov.hmrc.thirdpartyapplication.mocks.repository

import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import org.mockito.verification.VerificationMode
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifier
import scala.concurrent.Future.{failed, successful}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.domain.models.UserId

trait SubscriptionRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseSubscriptionRepoMock {
    def aMock: SubscriptionRepository

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object Fetch {

      def thenReturn(subs: ApiIdentifier*) =
        when(aMock.getSubscriptions(*[ApplicationId])).thenReturn(successful(subs.toList))

      def thenReturnWhen(id: ApplicationId)(subs: ApiIdentifier*) =
        when(aMock.getSubscriptions(eqTo(id))).thenReturn(successful(subs.toList))
    }

    object GetSubscribers {

      def thenReturnWhen(apiIdentifier: ApiIdentifier)(subs: ApplicationId*) =
        when(aMock.getSubscribers(eqTo(apiIdentifier))).thenReturn(successful(subs.toSet))

      def thenReturn(apiIdentifier: ApiIdentifier)(subs: Set[ApplicationId]) =
        when(aMock.getSubscribers(eqTo(apiIdentifier))).thenReturn(successful(subs))

      def thenFailWith(ex: Exception) =
        when(aMock.getSubscribers(*[ApiIdentifier])).thenReturn(failed(ex))
    }

    object GetSubscriptionsForDeveloper {

      def thenReturnWhen(userId: UserId)(apis: Set[ApiIdentifier]) =
        when(aMock.getSubscriptionsForDeveloper(eqTo(userId))).thenReturn(successful(apis))

      def thenFailWith(ex: Exception) =
        when(aMock.getSubscriptionsForDeveloper(*[UserId])).thenReturn(failed(ex))
    }

    object Remove {

      def thenReturnHasSucceeded() =
        when(aMock.remove(*[ApplicationId], *[ApiIdentifier])).thenReturn(successful(HasSucceeded))

      def verifyCalledWith(appId: ApplicationId, apiIdentifier: ApiIdentifier) =
        verify.remove(eqTo(appId), eqTo(apiIdentifier))
    }
  }

  object SubscriptionRepoMock extends BaseSubscriptionRepoMock {

    val aMock = mock[SubscriptionRepository]
  }
}
