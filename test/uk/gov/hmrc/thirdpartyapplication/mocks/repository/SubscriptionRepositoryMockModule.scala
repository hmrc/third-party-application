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

package uk.gov.hmrc.thirdpartyapplication.mocks.repository

import scala.concurrent.Future.{failed, successful}

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._

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

    object IsSubscribed {

      def isTrue() =
        when(aMock.isSubscribed(*[ApplicationId], *[ApiIdentifier])).thenReturn(successful(true))

      def isFalse() =
        when(aMock.isSubscribed(*[ApplicationId], *[ApiIdentifier])).thenReturn(successful(false))
    }

    object GetSubscribers {

      def thenReturnWhen(apiIdentifier: ApiIdentifier)(subs: ApplicationId*) =
        when(aMock.getSubscribers(eqTo(apiIdentifier))).thenReturn(successful(subs.toSet))

      def thenReturn(apiIdentifier: ApiIdentifier)(subs: Set[ApplicationId]) =
        when(aMock.getSubscribers(eqTo(apiIdentifier))).thenReturn(successful(subs))

      def thenFailWith(ex: Exception) =
        when(aMock.getSubscribers(*[ApiIdentifier])).thenReturn(failed(ex))
    }

    object Add {

      def succeeds() =
        when(aMock.add(*[ApplicationId], *[ApiIdentifier])).thenReturn(successful(HasSucceeded))
    }

    object Remove {

      def succeeds() =
        when(aMock.remove(*[ApplicationId], *[ApiIdentifier])).thenReturn(successful(HasSucceeded))

      def thenReturnHasSucceeded() = succeeds()

      def verifyCalledWith(appId: ApplicationId, apiIdentifier: ApiIdentifier) =
        verify.remove(eqTo(appId), eqTo(apiIdentifier))
    }
  }

  object SubscriptionRepoMock extends BaseSubscriptionRepoMock {

    val aMock = mock[SubscriptionRepository]
  }
}
