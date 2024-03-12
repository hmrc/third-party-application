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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService
import commands.deleteapplication.DeleteApplicationProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.redirects.RedirectUrisProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.responsibleindividual.RIProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.sandbox.SandboxProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.gatekeeper.GatekeeperProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.production.ProductionProcessor

@Singleton
class ApplicationCommandDispatcher @Inject() (
    applicationRepository: ApplicationRepository,
    notificationService: NotificationService,
    apiPlatformEventService: ApiPlatformEventService,
    auditService: AuditService,
    deleteApplicationProcessor: DeleteApplicationProcessor,
    redirectUrisProcessor: RedirectUrisProcessor,
    riProcessor: RIProcessor,
    sandboxProcessor: SandboxProcessor,
    gatekeeperProcessor: GatekeeperProcessor,
    productionProcessor: ProductionProcessor,

    addClientSecretCmdHdlr: AddClientSecretCommandHandler,
    removeClientSecretCmdHdlr: RemoveClientSecretCommandHandler,
    
    addCollaboratorCmdHdlr: AddCollaboratorCommandHandler,
    removeCollaboratorCmdHdlr: RemoveCollaboratorCommandHandler,

    subscribeToApiCmdHdlr: SubscribeToApiCommandHandler,
    unsubscribeFromApiCmdHdlr: UnsubscribeFromApiCommandHandler,
    
    changeIpAllowlistCommandHandler: ChangeIpAllowlistCommandHandler

  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  import cats.implicits._
  import CommandHandler._

  val E = EitherTHelper.make[CommandHandler.Failures]

  def dispatch(applicationId: ApplicationId, command: ApplicationCommand, verifiedCollaborators: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): AppCmdResultT = {
    for {
      app               <- E.fromOptionF(applicationRepository.fetch(applicationId), NonEmptyList.one(CommandFailures.ApplicationNotFound))
      updateResults     <- process(app,command)
      (savedApp, events) = updateResults

      _ <- E.liftF(apiPlatformEventService.applyEvents(events))
      _ <- E.liftF(auditService.applyEvents(savedApp, events))
      _ <- E.liftF(notificationService.sendNotifications(savedApp, events, verifiedCollaborators))
    } yield (savedApp, events)
  }

  // scalastyle:off cyclomatic.complexity
  private def process(app: StoredApplication, command: ApplicationCommand)(implicit hc: HeaderCarrier): AppCmdResultT = {
    import ApplicationCommands._
    command match {
      case cmd: RedirectUriMixin => redirectUrisProcessor.process(app, cmd)
      
      case cmd: ResponsibleIndividualMixin => riProcessor.process(app, cmd)

      case cmd: GatekeeperMixin => gatekeeperProcessor.process(app, cmd)
      
      case cmd: DeleteApplicationMixin => deleteApplicationProcessor.process(app, cmd)

      case cmd: AddCollaborator    => addCollaboratorCmdHdlr.process(app, cmd)
      case cmd: RemoveCollaborator => removeCollaboratorCmdHdlr.process(app, cmd)

      case cmd: AddClientSecret    => addClientSecretCmdHdlr.process(app, cmd)
      case cmd: RemoveClientSecret => removeClientSecretCmdHdlr.process(app, cmd)

      case cmd: SubscribeToApi     => subscribeToApiCmdHdlr.process(app, cmd)
      case cmd: UnsubscribeFromApi => unsubscribeFromApiCmdHdlr.process(app, cmd)

      
      // Sandbox application changing
      case cmd: SandboxMixin => sandboxProcessor.process(app, cmd)
      
      // Production application changing
      case cmd: ProductionMixin => productionProcessor.process(app, cmd)
      
      case cmd: ChangeIpAllowlist => changeIpAllowlistCommandHandler.process(app, cmd)
    }
  }
  // scalastyle:on cyclomatic.complexity
}
