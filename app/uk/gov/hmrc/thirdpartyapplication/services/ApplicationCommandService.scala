/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.{EitherT, NonEmptyList}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, CommandFailures}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ApplicationCommandService @Inject() (
    val applicationCommandDispatcher: ApplicationCommandDispatcher,
    val applicationCommandAuthenticator: ApplicationCommandAuthenticator
  )(implicit ec: ExecutionContext
  ) extends ApplicationLogger {

  import CommandHandler._

  val E = EitherTHelper.make[Failures]
  // Failures      = NonEmptyList[CommandFailure]

  def authenticateAndDispatch(applicationId: ApplicationId, command: ApplicationCommand, collaboratorsToNotify: Set[LaxEmailAddress])(implicit hc: HeaderCarrier)
      : EitherT[Future, Failures, Success] = {
    // We need to break out isAuthorised so we check Stride if GKUser OR if process we check some other thing
    (for {
      isAuthorised   <- E.liftF(applicationCommandAuthenticator.authenticateCommand(command))
      dispatchResult <- applicationCommandDispatcher.dispatch(applicationId, command, collaboratorsToNotify)
      result         <- E.cond(
                          isAuthorised,
                          dispatchResult,
                          NonEmptyList.one(CommandFailures.InsufficientPrivileges("Not authenticated"))
                        )
    } yield result)
  }
}
