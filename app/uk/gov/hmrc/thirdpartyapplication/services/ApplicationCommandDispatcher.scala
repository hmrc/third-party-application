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

import cats.data.NonEmptyChain

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, _}
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand,ApplicationCommands}

@Singleton
class ApplicationCommandDispatcher @Inject() (
    applicationRepository: ApplicationRepository,
    notificationService: NotificationService,
    apiPlatformEventService: ApiPlatformEventService,
    auditService: AuditService,
    addClientSecretCommandHandler: AddClientSecretCommandHandler,
    removeClientSecretCommandHandler: RemoveClientSecretCommandHandler,
    changeProductionApplicationNameCmdHdlr: ChangeProductionApplicationNameCommandHandler,
    addCollaboratorCommandHandler: AddCollaboratorCommandHandler,
    removeCollaboratorCommandHandler: RemoveCollaboratorCommandHandler,
    changeProductionApplicationPrivacyPolicyLocationCmdHdlr: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
    changeProductionApplicationTermsAndConditionsLocationCmdHdlr: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
    changeResponsibleIndividualToSelfCommandHandler: ChangeResponsibleIndividualToSelfCommandHandler,
    changeResponsibleIndividualToOtherCommandHandler: ChangeResponsibleIndividualToOtherCommandHandler,
    verifyResponsibleIndividualCommandHandler: VerifyResponsibleIndividualCommandHandler,
    declineResponsibleIndividualCommandHandler: DeclineResponsibleIndividualCommandHandler,
    declineResponsibleIndividualDidNotVerifyCommandHandler: DeclineResponsibleIndividualDidNotVerifyCommandHandler,
    declineApplicationApprovalRequestCommandHandler: DeclineApplicationApprovalRequestCommandHandler,
    deleteApplicationByCollaboratorCommandHandler: DeleteApplicationByCollaboratorCommandHandler,
    deleteApplicationByGatekeeperCommandHandler: DeleteApplicationByGatekeeperCommandHandler,
    deleteUnusedApplicationCommandHandler: DeleteUnusedApplicationCommandHandler,
    deleteProductionCredentialsApplicationCommandHandler: DeleteProductionCredentialsApplicationCommandHandler,
    subscribeToApiCommandHandler: SubscribeToApiCommandHandler,
    unsubscribeFromApiCommandHandler: UnsubscribeFromApiCommandHandler,
    updateRedirectUrisCommandHandler: UpdateRedirectUrisCommandHandler
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  import cats.implicits._
  import CommandHandler._

  val E = EitherTHelper.make[CommandHandler.Failures]

  def dispatch(applicationId: ApplicationId, command: ApplicationCommand, verifiedCollaborators: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): ResultT = {
    for {
      app               <- E.fromOptionF(applicationRepository.fetch(applicationId), NonEmptyChain(CommandFailures.ApplicationNotFound))
      updateResults     <- processUpdate(app, command)
      (savedApp, events) = updateResults

      _ <- E.liftF(apiPlatformEventService.applyEvents(events))
      _ <- E.liftF(auditService.applyEvents(savedApp, events))
      _ <- E.liftF(notificationService.sendNotifications(savedApp, events, verifiedCollaborators))
    } yield (savedApp, events)
  }

  // scalastyle:off cyclomatic.complexity
  private def processUpdate(app: ApplicationData, command: ApplicationCommand)(implicit hc: HeaderCarrier): ResultT = {
    import ApplicationCommands._
    command match {
      case cmd: AddCollaborator    => addCollaboratorCommandHandler.process(app, cmd)
      case cmd: RemoveCollaborator => removeCollaboratorCommandHandler.process(app, cmd)

      case cmd: AddClientSecret                                       => addClientSecretCommandHandler.process(app, cmd)
      case cmd: RemoveClientSecret                                    => removeClientSecretCommandHandler.process(app, cmd)
      case cmd: ChangeProductionApplicationName                       => changeProductionApplicationNameCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationPrivacyPolicyLocation      => changeProductionApplicationPrivacyPolicyLocationCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationTermsAndConditionsLocation => changeProductionApplicationTermsAndConditionsLocationCmdHdlr.process(app, cmd)
      case cmd: ChangeResponsibleIndividualToSelf                     => changeResponsibleIndividualToSelfCommandHandler.process(app, cmd)
      case cmd: ChangeResponsibleIndividualToOther                    => changeResponsibleIndividualToOtherCommandHandler.process(app, cmd)
      case cmd: VerifyResponsibleIndividual                           => verifyResponsibleIndividualCommandHandler.process(app, cmd)
      case cmd: DeclineResponsibleIndividual                          => declineResponsibleIndividualCommandHandler.process(app, cmd)
      case cmd: DeclineResponsibleIndividualDidNotVerify              => declineResponsibleIndividualDidNotVerifyCommandHandler.process(app, cmd)
      case cmd: DeclineApplicationApprovalRequest                     => declineApplicationApprovalRequestCommandHandler.process(app, cmd)
      case cmd: DeleteApplicationByCollaborator                       => deleteApplicationByCollaboratorCommandHandler.process(app, cmd)
      case cmd: DeleteApplicationByGatekeeper                         => deleteApplicationByGatekeeperCommandHandler.process(app, cmd)
      case cmd: DeleteUnusedApplication                               => deleteUnusedApplicationCommandHandler.process(app, cmd)
      case cmd: DeleteProductionCredentialsApplication                => deleteProductionCredentialsApplicationCommandHandler.process(app, cmd)
      case cmd: SubscribeToApi                                        => subscribeToApiCommandHandler.process(app, cmd)
      case cmd: UnsubscribeFromApi                                    => unsubscribeFromApiCommandHandler.process(app, cmd)
      case cmd: UpdateRedirectUris                                    => updateRedirectUrisCommandHandler.process(app, cmd)
    }
  }
  // scalastyle:on cyclomatic.complexity
}
