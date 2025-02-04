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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.LoginRedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, CommandFailure, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, ApplicationEvents, EventId}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandService
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

trait ApplicationCommandServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {
  import cats.implicits._

  protected trait BaseApplicationCommandServiceMock {

    def aMock: ApplicationCommandService

    val mockEvents                          = NonEmptyList.of(mock[ApplicationEvent])
    val mockErrors: CommandHandler.Failures = NonEmptyList.one(CommandFailures.GenericFailure("Bang"))
    val E                                   = EitherTHelper.make[CommandHandler.Failures]

    object AuthenticateAndDispatch {

      def succeedsWith(applicationData: StoredApplication) = {
        val success: CommandHandler.Success = (applicationData, mockEvents)
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(E.pure(success))
      }

      def thenReturnSuccessOn(cmd: ApplicationCommand)(applicationData: StoredApplication) = {
        val success: CommandHandler.Success = (applicationData, mockEvents)
        when(aMock.authenticateAndDispatch(*[ApplicationId], eqTo(cmd), *[Set[LaxEmailAddress]])(*)).thenReturn(E.pure(success))
      }

      def thenReturnSuccess(applicationData: StoredApplication) = {
        val success: CommandHandler.Success = (applicationData, mockEvents)
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(E.pure(success))
      }

      def thenReturnCommandSuccess(applicationData: StoredApplication) = {
        val dummyEvents                     =
          NonEmptyList.one(ApplicationEvents.LoginRedirectUrisUpdatedV2(
            EventId.random,
            ApplicationId.random,
            FixedClock.instant,
            Actors.AppCollaborator("someuser".toLaxEmail),
            List.empty,
            List(new LoginRedirectUri("new URI"))
          ))
        val success: CommandHandler.Success = (applicationData, dummyEvents)
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(E.pure(success))
      }

      def thenReturnSuccess(applicationData: StoredApplication, event: ApplicationEvent, moreEvents: ApplicationEvent*) = {
        val success: CommandHandler.Success = (applicationData, NonEmptyList.of(event, moreEvents: _*))
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(E.pure(success))
      }

      def thenReturnFailed() =
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(EitherT.leftT(mockErrors))

      def thenReturnFailedFor(appId: ApplicationId) =
        when(aMock.authenticateAndDispatch(eqTo(appId), *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(EitherT.leftT(mockErrors))

      def thenReturnFailed(fails: CommandFailure, otherFails: CommandFailure*) =
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(EitherT.leftT(NonEmptyList.of(fails, otherFails: _*)))

      def thenReturnFailed(msg: String, otherMsgs: String*) =
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(EitherT.leftT(NonEmptyList.of(
          CommandFailures.GenericFailure(msg),
          otherMsgs.map(CommandFailures.GenericFailure(_)): _*
        )))

      def thenReturnAuthFailed(msg: String, otherMsgs: String*) =
        when(aMock.authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)).thenReturn(EitherT.leftT(NonEmptyList.of(
          CommandFailures.InsufficientPrivileges(msg),
          otherMsgs.map(CommandFailures.GenericFailure(_)): _*
        )))

      def verifyNeverCalled =
        verify(aMock, never).authenticateAndDispatch(*[ApplicationId], *[ApplicationCommand], *[Set[LaxEmailAddress]])(*)

      def verifyCalledWith(applicationId: ApplicationId) = {
        val captor = ArgCaptor[ApplicationCommand]
        verify(aMock).authenticateAndDispatch(eqTo(applicationId), captor.capture, *)(*)
        captor.value
      }

      def verifyCalledWith(applicationId: ApplicationId, command: ApplicationCommand) = {
        verify(aMock).authenticateAndDispatch(eqTo(applicationId), eqTo(command), *)(*)
      }
    }
  }

  object ApplicationCommandServiceMock extends BaseApplicationCommandServiceMock {
    val aMock = mock[ApplicationCommandService]
  }
}
