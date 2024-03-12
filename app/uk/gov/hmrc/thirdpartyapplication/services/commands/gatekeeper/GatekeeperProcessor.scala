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

package uk.gov.hmrc.thirdpartyapplication.services.commands.gatekeeper

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommands, GatekeeperMixin}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, _}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.GatekeeperMixin

@Singleton
class GatekeeperProcessor @Inject() (
    deleteApplicationByGatekeeperCmdHdlr: DeleteApplicationByGatekeeperCommandHandler,
    allowApplicationAutoDeleteCmdHdlr: AllowApplicationAutoDeleteCommandHandler,
    blockApplicationAutoDeleteCmdHdlr: BlockApplicationAutoDeleteCommandHandler,
    changeGrantLengthCmdHdlr: ChangeGrantLengthCommandHandler,
    changeRateLimitTierCmdHdlr: ChangeRateLimitTierCommandHandler,
    changeProductionApplicationNameCmdHdlr: ChangeProductionApplicationNameCommandHandler,
    declineApplicationApprovalRequestCommandHandler: DeclineApplicationApprovalRequestCommandHandler
    
  ) {
  import CommandHandler._
  import ApplicationCommands._

  def process(app: StoredApplication, command: GatekeeperMixin)(implicit hc: HeaderCarrier): AppCmdResultT = command match {
    case cmd: DeleteApplicationByGatekeeper          => deleteApplicationByGatekeeperCmdHdlr.process(app, cmd)
    case cmd: AllowApplicationAutoDelete             => allowApplicationAutoDeleteCmdHdlr.process(app, cmd)
    case cmd: BlockApplicationAutoDelete             => blockApplicationAutoDeleteCmdHdlr.process(app, cmd)
    case cmd: ChangeGrantLength => changeGrantLengthCmdHdlr.process(app, cmd)
    case cmd: ChangeRateLimitTier => changeRateLimitTierCmdHdlr.process(app, cmd)
    case cmd: ChangeProductionApplicationName                       => changeProductionApplicationNameCmdHdlr.process(app, cmd)
    case cmd: DeclineApplicationApprovalRequest  => declineApplicationApprovalRequestCommandHandler.process(app, cmd)
  }
}
