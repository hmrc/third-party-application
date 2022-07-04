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

package uk.gov.hmrc.thirdpartyapplication.mocks

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService

import scala.concurrent.Future

trait NotificationServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {

  protected trait BaseNotificationServiceMock {

    def aMock: NotificationService

    object SendNotifications {

      def thenReturnSuccess() =
        when(aMock.sendNotifications(*[ApplicationData], *)(*)).thenReturn(Future.successful(List(HasSucceeded)))
    }
  }

  object NotificationServiceMock extends BaseNotificationServiceMock {
    val aMock = mock[NotificationService]
  }
}
