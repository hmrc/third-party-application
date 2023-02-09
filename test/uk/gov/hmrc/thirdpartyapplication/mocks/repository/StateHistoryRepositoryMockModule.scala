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

import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

import org.mockito.captor.ArgCaptor
import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, State, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository

trait StateHistoryRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseStateHistoryRepoMock {
    def aMock: StateHistoryRepository

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object Insert {

      def thenAnswer() =
        when(aMock.insert(*)).thenAnswer((sh: StateHistory) => successful(sh))

      def thenFailsWith(ex: Exception) =
        when(aMock.insert(*)).thenReturn(failed(ex))

      def verifyCalledWith(sh: StateHistory) =
        verify.insert(eqTo(sh))

      def verifyNeverCalled() =
        verify(never).insert(*)

      def verifyCalled(): StateHistory = {
        val stateHistoryArgumentCaptor = ArgCaptor[StateHistory]
        verify.insert(stateHistoryArgumentCaptor)
        stateHistoryArgumentCaptor.value
      }
    }

    object Delete {
      def thenReturnHasSucceeded() = when(aMock.deleteByApplicationId(*[ApplicationId])).thenReturn(successful(HasSucceeded))
    }

    object FetchLatestByStateForApplication {

      def thenReturnWhen(id: ApplicationId, state: State.State)(value: StateHistory) =
        when(aMock.fetchLatestByStateForApplication(eqTo(id), eqTo(state))).thenReturn(successful(Some(value)))
    }

    object FetchLatestByState {

      def thenReturnWhen(state: State.State)(values: StateHistory*) =
        when(aMock.fetchByState(eqTo(state))).thenReturn(successful(values.toList))
    }

    object FetchByApplicationId {

      def thenReturnWhen(id: ApplicationId)(values: StateHistory*) =
        when(aMock.fetchByApplicationId(eqTo(id))).thenReturn(successful(values.toList))

      def thenFailWith(ex: Exception) =
        when(aMock.fetchByApplicationId(*[ApplicationId])).thenReturn(failed(ex))
    }

    object ApplyEvents {

      def succeeds() = {
        when(aMock.applyEvents(*)).thenReturn(Future.successful(HasSucceeded))
      }
    }

    object AddRecord {

      def succeeds() = {
        when(aMock.addStateHistoryRecord(*)).thenReturn(successful(HasSucceeded))
      }
    }
  }

  object StateHistoryRepoMock extends BaseStateHistoryRepoMock {

    val aMock = mock[StateHistoryRepository]
  }
}
