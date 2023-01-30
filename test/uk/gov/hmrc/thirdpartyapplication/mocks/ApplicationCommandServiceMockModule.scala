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

import scala.concurrent.ExecutionContext.Implicits.global

import cats.data.{EitherT, NonEmptyChain}
import cats.implicits.catsStdInstancesForFuture
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationCommand, ApplicationId}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandService

trait ApplicationCommandServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApplicationCommandServiceMock {

    def aMock: ApplicationCommandService

    object Update {

      def thenReturnSuccess(applicationData: ApplicationData) =
        when(aMock.update(*[ApplicationId], *[ApplicationCommand])(*)).thenReturn(EitherT.rightT(applicationData))

      def thenReturnSuccess(applicationId: ApplicationId, applicationData: ApplicationData) =
        when(aMock.update(eqTo(applicationId), *[ApplicationCommand])(*)).thenReturn(EitherT.rightT(applicationData))

      def thenReturnError(errorMsg: String) =
        when(aMock.update(*[ApplicationId], *[ApplicationCommand])(*)).thenReturn(EitherT.leftT(NonEmptyChain(errorMsg)))

      def thenReturnError(applicationId: ApplicationId, errorMsg: String) =
        when(aMock.update(eqTo(applicationId), *[ApplicationCommand])(*)).thenReturn(EitherT.leftT(NonEmptyChain(errorMsg)))

      def verifyNeverCalled =
        verify(aMock, never).update(*[ApplicationId], *[ApplicationCommand])(*)

      def verifyCalledWith(applicationId: ApplicationId) = {
        val captor = ArgCaptor[ApplicationCommand]
        verify(aMock).update(eqTo(applicationId), captor.capture)(*)
        captor.value
      }

      def verifyCalledWith(applicationId: ApplicationId, command: ApplicationCommand) = {
        verify(aMock).update(eqTo(applicationId), eqTo(command))(*)
      }
    }
  }

  object ApplicationCommandServiceMock extends BaseApplicationCommandServiceMock {
    val aMock = mock[ApplicationCommandService]
  }
}
