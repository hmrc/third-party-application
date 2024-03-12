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
import uk.gov.hmrc.thirdpartyapplication.services.commands.{AddClientSecretCommandHandler, ChangeGrantLengthCommandHandler, CommandHandler, _}
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService
import commands.deleteapplication.DeleteApplicationProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.deleteapplication.RedirectUrisProcessor

@Singleton
class ApplicationCommandDispatcher @Inject() (
    applicationRepository: ApplicationRepository,
    notificationService: NotificationService,
    apiPlatformEventService: ApiPlatformEventService,
    auditService: AuditService,
    deleteApplicationProcessor: DeleteApplicationProcessor,
    redirectUrisProcessor: RedirectUrisProcessor,
    addClientSecretCmdHdlr: AddClientSecretCommandHandler,
    addCollaboratorCmdHdlr: AddCollaboratorCommandHandler,
    removeClientSecretCmdHdlr: RemoveClientSecretCommandHandler,
    changeGrantLengthCmdHdlr: ChangeGrantLengthCommandHandler,
    changeRateLimitTierCmdHdlr: ChangeRateLimitTierCommandHandler,
    changeProductionApplicationNameCmdHdlr: ChangeProductionApplicationNameCommandHandler,
    removeCollaboratorCmdHdlr: RemoveCollaboratorCommandHandler,
    changeProductionApplicationPrivacyPolicyLocationCmdHdlr: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
    changeProductionApplicationTermsAndConditionsLocationCmdHdlr: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
    changeResponsibleIndividualToSelfCmdHdlr: ChangeResponsibleIndividualToSelfCommandHandler,
    changeResponsibleIndividualToOtherCmdHdlr: ChangeResponsibleIndividualToOtherCommandHandler,
    verifyResponsibleIndividualCmdHdlr: VerifyResponsibleIndividualCommandHandler,
    declineResponsibleIndividualCmdHdlr: DeclineResponsibleIndividualCommandHandler,
    declineResponsibleIndividualDidNotVerifyCmdHdlr: DeclineResponsibleIndividualDidNotVerifyCommandHandler,
    declineApplicationApprovalRequestCmdHdlr: DeclineApplicationApprovalRequestCommandHandler,
    subscribeToApiCmdHdlr: SubscribeToApiCommandHandler,
    unsubscribeFromApiCmdHdlr: UnsubscribeFromApiCommandHandler,
    changeIpAllowlistCommandHandler: ChangeIpAllowlistCommandHandler,
    changeSandboxApplicationNameCommandHandler: ChangeSandboxApplicationNameCommandHandler
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
      case cmd: AddCollaborator    => addCollaboratorCmdHdlr.process(app, cmd)
      case cmd: RemoveCollaborator => removeCollaboratorCmdHdlr.process(app, cmd)

      case cmd: AddClientSecret    => addClientSecretCmdHdlr.process(app, cmd)
      case cmd: RemoveClientSecret => removeClientSecretCmdHdlr.process(app, cmd)

      case cmd: SubscribeToApi     => subscribeToApiCmdHdlr.process(app, cmd)
      case cmd: UnsubscribeFromApi => unsubscribeFromApiCmdHdlr.process(app, cmd)

      case cmd: RedirectUriMixin => redirectUrisProcessor.process(app, cmd)

      case cmd: ChangeGrantLength => changeGrantLengthCmdHdlr.process(app, cmd)

      case cmd: ChangeRateLimitTier => changeRateLimitTierCmdHdlr.process(app, cmd)

      // Sandbox application changing
      case cmd: ChangeSandboxApplicationName                  => changeSandboxApplicationNameCommandHandler.process(app, cmd)
      case cmd: ChangeSandboxApplicationDescription           => ???
      case cmd: ChangeSandboxApplicationPrivacyPolicyUrl      => ???
      case cmd: ChangeSandboxApplicationTermsAndConditionsUrl => ???
      case cmd: ClearSandboxApplicationDescription            => ???
      case cmd: RemoveSandboxApplicationPrivacyPolicyUrl      => ???
      case cmd: RemoveSandboxApplicationTermsAndConditionsUrl => ???

      // Production application changing
      case cmd: ChangeProductionApplicationName                       => changeProductionApplicationNameCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationPrivacyPolicyLocation      => changeProductionApplicationPrivacyPolicyLocationCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationTermsAndConditionsLocation => changeProductionApplicationTermsAndConditionsLocationCmdHdlr.process(app, cmd)

      case cmd: ChangeResponsibleIndividualToSelf        => changeResponsibleIndividualToSelfCmdHdlr.process(app, cmd)
      case cmd: ChangeResponsibleIndividualToOther       => changeResponsibleIndividualToOtherCmdHdlr.process(app, cmd)
      case cmd: VerifyResponsibleIndividual              => verifyResponsibleIndividualCmdHdlr.process(app, cmd)
      case cmd: DeclineResponsibleIndividual             => declineResponsibleIndividualCmdHdlr.process(app, cmd)
      case cmd: DeclineResponsibleIndividualDidNotVerify => declineResponsibleIndividualDidNotVerifyCmdHdlr.process(app, cmd)

      case cmd: DeclineApplicationApprovalRequest => declineApplicationApprovalRequestCmdHdlr.process(app, cmd)

      case cmd: DeleteApplicationMixin => deleteApplicationProcessor.process(app, cmd)

      case cmd: ChangeIpAllowlist => changeIpAllowlistCommandHandler.process(app, cmd)
    }
  }
  // scalastyle:on cyclomatic.complexity
}
