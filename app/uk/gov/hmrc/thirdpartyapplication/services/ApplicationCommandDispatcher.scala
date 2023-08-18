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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, ApplicationCommands, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.commands.{AddClientSecretCommandHandler, ChangeGrantLengthCommandHandler, CommandHandler, _}
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService

@Singleton
class ApplicationCommandDispatcher @Inject() (
    applicationRepository: ApplicationRepository,
    notificationService: NotificationService,
    apiPlatformEventService: ApiPlatformEventService,
    auditService: AuditService,
    addClientSecretCmdHdlr: AddClientSecretCommandHandler,
    addCollaboratorCmdHdlr: AddCollaboratorCommandHandler,
    addRedirectUriCommandHandle: AddRedirectUriCommandHandler,
    removeClientSecretCmdHdlr: RemoveClientSecretCommandHandler,
    changeGrantLengthCmdHdlr: ChangeGrantLengthCommandHandler,
    changeRateLimitTierCmdHdlr: ChangeRateLimitTierCommandHandler,
    changeProductionApplicationNameCmdHdlr: ChangeProductionApplicationNameCommandHandler,
    removeCollaboratorCmdHdlr: RemoveCollaboratorCommandHandler,
    changeProductionApplicationPrivacyPolicyLocationCmdHdlr: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
    changeProductionApplicationTermsAndConditionsLocationCmdHdlr: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
    changeResponsibleIndividualToSelfCmdHdlr: ChangeResponsibleIndividualToSelfCommandHandler,
    changeResponsibleIndividualToOtherCmdHdlr: ChangeResponsibleIndividualToOtherCommandHandler,
    changeRedirectUriCmdHdlr: ChangeRedirectUriCommandHandler,
    verifyResponsibleIndividualCmdHdlr: VerifyResponsibleIndividualCommandHandler,
    declineResponsibleIndividualCmdHdlr: DeclineResponsibleIndividualCommandHandler,
    declineResponsibleIndividualDidNotVerifyCmdHdlr: DeclineResponsibleIndividualDidNotVerifyCommandHandler,
    declineApplicationApprovalRequestCmdHdlr: DeclineApplicationApprovalRequestCommandHandler,
    deleteApplicationByCollaboratorCmdHdlr: DeleteApplicationByCollaboratorCommandHandler,
    deleteApplicationByGatekeeperCmdHdlr: DeleteApplicationByGatekeeperCommandHandler,
    deleteUnusedApplicationCmdHdlr: DeleteUnusedApplicationCommandHandler,
    deleteProductionCredentialsApplicationCmdHdlr: DeleteProductionCredentialsApplicationCommandHandler,
    deleteRedirectUriCmdHdlr: DeleteRedirectUriCommandHandler,
    subscribeToApiCmdHdlr: SubscribeToApiCommandHandler,
    unsubscribeFromApiCmdHdlr: UnsubscribeFromApiCommandHandler,
    updateRedirectUrisCmdHdlr: UpdateRedirectUrisCommandHandler,
    allowApplicationAutoDeleteCmdHdlr: AllowApplicationAutoDeleteCommandHandler,
    blockApplicationAutoDeleteCmdHdlr: BlockApplicationAutoDeleteCommandHandler
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  import cats.implicits._
  import CommandHandler._

  val E = EitherTHelper.make[CommandHandler.Failures]

  def dispatch(applicationId: ApplicationId, command: ApplicationCommand, verifiedCollaborators: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): AppCmdResultT = {
    for {
      app               <- E.fromOptionF(applicationRepository.fetch(applicationId), NonEmptyList.one(CommandFailures.ApplicationNotFound))
      updateResults     <- processUpdate(app, command)
      (savedApp, events) = updateResults

      _ <- E.liftF(apiPlatformEventService.applyEvents(events))
      _ <- E.liftF(auditService.applyEvents(savedApp, events))
      _ <- E.liftF(notificationService.sendNotifications(savedApp, events, verifiedCollaborators))
    } yield (savedApp, events)
  }

  // scalastyle:off cyclomatic.complexity
  private def processUpdate(app: ApplicationData, command: ApplicationCommand)(implicit hc: HeaderCarrier): AppCmdResultT = {
    import ApplicationCommands._
    command match {
      case cmd: AddCollaborator                                       => addCollaboratorCmdHdlr.process(app, cmd)
      case cmd: RemoveCollaborator                                    => removeCollaboratorCmdHdlr.process(app, cmd)
      case cmd: AddClientSecret                                       => addClientSecretCmdHdlr.process(app, cmd)
      case cmd: AddRedirectUri                                        => addRedirectUriCommandHandle.process(app, cmd)
      case cmd: RemoveClientSecret                                    => removeClientSecretCmdHdlr.process(app, cmd)
      case cmd: ChangeGrantLength                                     => changeGrantLengthCmdHdlr.process(app, cmd)
      case cmd: ChangeRateLimitTier                                   => changeRateLimitTierCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationName                       => changeProductionApplicationNameCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationPrivacyPolicyLocation      => changeProductionApplicationPrivacyPolicyLocationCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationTermsAndConditionsLocation => changeProductionApplicationTermsAndConditionsLocationCmdHdlr.process(app, cmd)
      case cmd: ChangeRedirectUri                                     => changeRedirectUriCmdHdlr.process(app, cmd)
      case cmd: ChangeResponsibleIndividualToSelf                     => changeResponsibleIndividualToSelfCmdHdlr.process(app, cmd)
      case cmd: ChangeResponsibleIndividualToOther                    => changeResponsibleIndividualToOtherCmdHdlr.process(app, cmd)
      case cmd: VerifyResponsibleIndividual                           => verifyResponsibleIndividualCmdHdlr.process(app, cmd)
      case cmd: DeclineResponsibleIndividual                          => declineResponsibleIndividualCmdHdlr.process(app, cmd)
      case cmd: DeclineResponsibleIndividualDidNotVerify              => declineResponsibleIndividualDidNotVerifyCmdHdlr.process(app, cmd)
      case cmd: DeclineApplicationApprovalRequest                     => declineApplicationApprovalRequestCmdHdlr.process(app, cmd)
      case cmd: DeleteApplicationByCollaborator                       => deleteApplicationByCollaboratorCmdHdlr.process(app, cmd)
      case cmd: DeleteApplicationByGatekeeper                         => deleteApplicationByGatekeeperCmdHdlr.process(app, cmd)
      case cmd: DeleteUnusedApplication                               => deleteUnusedApplicationCmdHdlr.process(app, cmd)
      case cmd: DeleteProductionCredentialsApplication                => deleteProductionCredentialsApplicationCmdHdlr.process(app, cmd)
      case cmd: DeleteRedirectUri                                     => deleteRedirectUriCmdHdlr.process(app, cmd)
      case cmd: SubscribeToApi                                        => subscribeToApiCmdHdlr.process(app, cmd)
      case cmd: UnsubscribeFromApi                                    => unsubscribeFromApiCmdHdlr.process(app, cmd)
      case cmd: UpdateRedirectUris                                    => updateRedirectUrisCmdHdlr.process(app, cmd)
      case cmd: AllowApplicationAutoDelete                            => allowApplicationAutoDeleteCmdHdlr.process(app, cmd)
      case cmd: BlockApplicationAutoDelete                            => blockApplicationAutoDeleteCmdHdlr.process(app, cmd)
    }
  }
  // scalastyle:on cyclomatic.complexity
}
