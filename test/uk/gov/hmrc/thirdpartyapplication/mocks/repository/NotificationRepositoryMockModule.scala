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

import scala.concurrent.Future.successful

import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.Notification
import uk.gov.hmrc.thirdpartyapplication.repository.NotificationRepository

trait NotificationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseNotificationRepositoryMock {
    def aMock: NotificationRepository

    object CreateEntity {
      def thenReturnSuccess() = when(aMock.createEntity(*[Notification])).thenAnswer(successful(true))

      def verifyCalledWith() = {
        val captor = ArgCaptor[Notification]
        verify(aMock).createEntity(captor.capture)
        captor.value
      }
    }

    object DeleteAllByApplicationId {
      def thenReturnSuccess()                    = when(aMock.deleteAllByApplicationId(*[ApplicationId])).thenAnswer(successful(HasSucceeded))
      def verifyCalledWith(appId: ApplicationId) = verify(aMock).deleteAllByApplicationId(appId)
    }

    object ApplyEvents {

      def succeeds() = {
        when(aMock.applyEvents(*)).thenReturn(successful(HasSucceeded))
      }
    }
  }

  object NotificationRepositoryMock extends BaseNotificationRepositoryMock {
    val aMock = mock[NotificationRepository]
  }
}
