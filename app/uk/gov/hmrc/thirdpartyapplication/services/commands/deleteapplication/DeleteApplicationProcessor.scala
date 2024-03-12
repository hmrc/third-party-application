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

package uk.gov.hmrc.thirdpartyapplication.services.commands.deleteapplication

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommands, DeleteApplicationMixin}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, _}

@Singleton
class DeleteApplicationProcessor @Inject() (
    deleteApplicationByCollaboratorCmdHdlr: DeleteApplicationByCollaboratorCommandHandler,
    deleteApplicationByGatekeeperCmdHdlr: DeleteApplicationByGatekeeperCommandHandler,
    deleteUnusedApplicationCmdHdlr: DeleteUnusedApplicationCommandHandler,
    deleteProductionCredentialsApplicationCmdHdlr: DeleteProductionCredentialsApplicationCommandHandler,
    allowApplicationAutoDeleteCmdHdlr: AllowApplicationAutoDeleteCommandHandler,
    blockApplicationAutoDeleteCmdHdlr: BlockApplicationAutoDeleteCommandHandler
  ) {
  import CommandHandler._
  import ApplicationCommands._

  def process(app: StoredApplication, command: DeleteApplicationMixin)(implicit hc: HeaderCarrier): AppCmdResultT = command match {
    case cmd: DeleteApplicationByCollaborator        => deleteApplicationByCollaboratorCmdHdlr.process(app, cmd)
    case cmd: DeleteApplicationByGatekeeper          => deleteApplicationByGatekeeperCmdHdlr.process(app, cmd)
    case cmd: DeleteUnusedApplication                => deleteUnusedApplicationCmdHdlr.process(app, cmd)
    case cmd: DeleteProductionCredentialsApplication => deleteProductionCredentialsApplicationCmdHdlr.process(app, cmd)
    case cmd: AllowApplicationAutoDelete             => allowApplicationAutoDeleteCmdHdlr.process(app, cmd)
    case cmd: BlockApplicationAutoDelete             => blockApplicationAutoDeleteCmdHdlr.process(app, cmd)
  }
}
