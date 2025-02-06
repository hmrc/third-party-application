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

package uk.gov.hmrc.thirdpartyapplication.services.commands.redirecturi

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.data.EitherT

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommands, CommandFailures, PostLogoutRedirectCommand}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class PostLogoutRedirectUriCommandsProcessor @Inject() (
    updatePostLogoutRedirectUrisCmdHdlr: UpdatePostLogoutRedirectUrisCommandHandler
  )(implicit ec: ExecutionContext
  ) {
  import CommandHandler._
  import ApplicationCommands._
  import Implicits._

  val NotYetSupported: AppCmdResultT = EitherT(CommandFailures.GenericFailure(s"Command not yet supported").asFailure)

  def process(app: StoredApplication, command: PostLogoutRedirectCommand): AppCmdResultT = command match {
    case cmd: AddPostLogoutRedirectUri     => NotYetSupported
    case cmd: ChangePostLogoutRedirectUri  => NotYetSupported
    case cmd: DeletePostLogoutRedirectUri  => NotYetSupported
    case cmd: UpdatePostLogoutRedirectUris => updatePostLogoutRedirectUrisCmdHdlr.process(app, cmd)
  }
}
