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

import scala.concurrent.Future

import cats.data.NonEmptyList
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.services.ApiPlatformEventService
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

trait ApiPlatformEventServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {

  protected trait BaseApiPlatformEventServiceMockModule {

    def aMock: ApiPlatformEventService

    object ApplyEvents {

      def succeeds = {
        when(aMock.applyEvents(*)(*)).thenReturn(Future.successful(true))
      }

      def verifyCalledWith(events: NonEmptyList[UpdateApplicationEvent]) = {
        verify(aMock).applyEvents(eqTo(events))(*)
      }
    }
  }

  object ApiPlatformEventServiceMock extends BaseApiPlatformEventServiceMockModule {
    val aMock = mock[ApiPlatformEventService]
  }
}
