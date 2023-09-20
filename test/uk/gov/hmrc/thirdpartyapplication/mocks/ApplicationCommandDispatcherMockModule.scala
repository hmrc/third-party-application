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

import cats.data.{EitherT, NonEmptyList}
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, CommandFailure, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, ApplicationEvents, EventId}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandDispatcher
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

trait ApplicationCommandDispatcherMockModule extends MockitoSugar with ArgumentMatchersSugar {
  import cats.implicits._

  protected trait BaseApplicationCommandDispatcherMock {

    def aMock: ApplicationCommandDispatcher

    val mockEvents                          = NonEmptyList.of(mock[ApplicationEvent])
    val mockErrors: CommandHandler.Failures = NonEmptyList.one(CommandFailures.GenericFailure("Bang"))
    val E                                   = EitherTHelper.make[CommandHandler.Failures]

    object Dispatch {

      def succeedsWith(applicationData: ApplicationData) = {
        val success: CommandHandler.Success = (applicationData, mockEvents)
        when(aMock.dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)).thenReturn(E.pure(success))
      }

      def thenReturnSuccessOn(cmd: ApplicationCommand)(applicationData: ApplicationData) = {
        val success: CommandHandler.Success = (applicationData, mockEvents)
        when(aMock.dispatch(*[ApplicationId], eqTo(cmd), *)(*)).thenReturn(E.pure(success))
      }

      def thenReturnSuccess(applicationData: ApplicationData) = {
        val success: CommandHandler.Success = (applicationData, mockEvents)
        when(aMock.dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)).thenReturn(E.pure(success))
      }

      def thenReturnCommandSuccess(applicationData: ApplicationData) = {
        val dummyEvents                     =
          NonEmptyList.one(ApplicationEvents.RedirectUrisUpdatedV2(
            EventId.random,
            ApplicationId.random,
            FixedClock.instant,
            Actors.AppCollaborator("someuser".toLaxEmail),
            List.empty,
            List("new URI")
          ))
        val success: CommandHandler.Success = (applicationData, dummyEvents)
        when(aMock.dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)).thenReturn(E.pure(success))
      }

      def thenReturnSuccess(applicationData: ApplicationData, event: ApplicationEvent, moreEvents: ApplicationEvent*) = {
        val success: CommandHandler.Success = (applicationData, NonEmptyList.of(event, moreEvents: _*))
        when(aMock.dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)).thenReturn(E.pure(success))
      }

      def thenReturnFailed() =
        when(aMock.dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)).thenReturn(EitherT.leftT(mockErrors))

      def thenReturnFailedFor(appId: ApplicationId) =
        when(aMock.dispatch(eqTo(appId), *[ApplicationCommand], *)(*)).thenReturn(EitherT.leftT(mockErrors))

      def thenReturnFailed(fails: CommandFailure, otherFails: CommandFailure*) =
        when(aMock.dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)).thenReturn(EitherT.leftT(NonEmptyList.of(fails, otherFails: _*)))

      def thenReturnFailed(msg: String, otherMsgs: String*) =
        when(aMock.dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)).thenReturn(EitherT.leftT(NonEmptyList.of(
          CommandFailures.GenericFailure(msg),
          otherMsgs.map(CommandFailures.GenericFailure(_)): _*
        )))

      def verifyNeverCalled =
        verify(aMock, never).dispatch(*[ApplicationId], *[ApplicationCommand], *)(*)

      def verifyCalledWith(applicationId: ApplicationId) = {
        val captor = ArgCaptor[ApplicationCommand]
        verify(aMock).dispatch(eqTo(applicationId), captor.capture, *)(*)
        captor.value
      }

      def verifyCalledWith(applicationId: ApplicationId, command: ApplicationCommand) = {
        verify(aMock).dispatch(eqTo(applicationId), eqTo(command), *)(*)
      }
    }
  }

  object ApplicationCommandDispatcherMock extends BaseApplicationCommandDispatcherMock {
    val aMock = mock[ApplicationCommandDispatcher]
  }
}
