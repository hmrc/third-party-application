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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.models.db.Notification
import uk.gov.hmrc.thirdpartyapplication.repository.NotificationRepository
import scala.concurrent.Future.successful

trait NotificationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseNotificationRepositoryMock {
    def aMock: NotificationRepository

    object CreateEntity {
      def thenReturnSuccess()                          = when(aMock.createEntity(*[Notification])).thenAnswer(successful(true))
      def verifyCalledWith(notification: Notification) = verify(aMock).createEntity(notification)
    }
  }

  object NotificationRepositoryMock extends BaseNotificationRepositoryMock {
    val aMock = mock[NotificationRepository]
  }
}
