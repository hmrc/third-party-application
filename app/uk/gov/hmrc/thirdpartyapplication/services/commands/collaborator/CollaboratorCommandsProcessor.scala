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

package uk.gov.hmrc.thirdpartyapplication.services.commands.collaborator

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommands, CollaboratorCommand}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class CollaboratorCommandsProcessor @Inject() (
    addCollaboratorCmdHdlr: AddCollaboratorCommandHandler,
    removeCollaboratorCmdHdlr: RemoveCollaboratorCommandHandler
  ) {
  import CommandHandler._
  import ApplicationCommands._

  def process(app: StoredApplication, command: CollaboratorCommand): AppCmdResultT = command match {
    case cmd: AddCollaborator    => addCollaboratorCmdHdlr.process(app, cmd)
    case cmd: RemoveCollaborator => removeCollaboratorCmdHdlr.process(app, cmd)
  }
}
