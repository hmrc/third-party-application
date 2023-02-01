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
import scala.concurrent.{ExecutionContext, Future}

import cats.data.{EitherT, NonEmptyChain}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService
import uk.gov.hmrc.thirdpartyapplication.repository._

@Singleton
class ApplicationCommandDispatcher @Inject() (
    applicationRepository: ApplicationRepository,

    notificationService: NotificationService,
    apiPlatformEventService: ApiPlatformEventService,
    auditService: AuditService,

    addClientSecretCommandHandler: AddClientSecretCommandHandler,
    removeClientSecretCommandHandler: RemoveClientSecretCommandHandler,
    updateRedirectUrisCommandHandler: UpdateRedirectUrisCommandHandler
    // changeProductionApplicationNameCmdHdlr: ChangeProductionApplicationNameCommandHandler,
    // changeProductionApplicationPrivacyPolicyLocationCmdHdlr: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
    // changeProductionApplicationTermsAndConditionsLocationCmdHdlr: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
    // changeResponsibleIndividualToSelfCommandHandler: ChangeResponsibleIndividualToSelfCommandHandler,
    // changeResponsibleIndividualToOtherCommandHandler: ChangeResponsibleIndividualToOtherCommandHandler,
    // verifyResponsibleIndividualCommandHandler: VerifyResponsibleIndividualCommandHandler,
    // declineResponsibleIndividualCommandHandler: DeclineResponsibleIndividualCommandHandler,
    // declineResponsibleIndividualDidNotVerifyCommandHandler: DeclineResponsibleIndividualDidNotVerifyCommandHandler,
    // declineApplicationApprovalRequestCommandHandler: DeclineApplicationApprovalRequestCommandHandler,
    // deleteApplicationByCollaboratorCommandHandler: DeleteApplicationByCollaboratorCommandHandler,
    // deleteApplicationByGatekeeperCommandHandler: DeleteApplicationByGatekeeperCommandHandler,
    // deleteUnusedApplicationCommandHandler: DeleteUnusedApplicationCommandHandler,
    // deleteProductionCredentialsApplicationCommandHandler: DeleteProductionCredentialsApplicationCommandHandler,
    // addCollaboratorCommandHandler: AddCollaboratorCommandHandler,
    // removeCollaboratorCommandHandler: RemoveCollaboratorCommandHandler,
    // subscribeToApiCommandHandler: SubscribeToApiCommandHandler,
    // unsubscribeFromApiCommandHandler: UnsubscribeFromApiCommandHandler,

  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {
    
  import cats.implicits._
  import CommandHandler2._
  
  val E = EitherTHelper.make[CommandFailures]

  def dispatch(applicationId: ApplicationId, command: ApplicationCommand)(implicit hc: HeaderCarrier): ResultT = {
    for {
      app           <- E.fromOptionF(applicationRepository.fetch(applicationId), NonEmptyChain(s"No application found with id $applicationId"))
      updateResults <- processUpdate(app, command)
      (savedApp, events) = updateResults

      _ <- E.liftF(apiPlatformEventService.applyEvents(events))
      _ <- E.liftF(auditService.applyEvents(savedApp, events))
      _ <- E.liftF(notificationService.sendNotifications(savedApp, events.collect { case evt: UpdateApplicationEvent with TriggersNotification => evt }))
    } yield (savedApp,events)
  }

  // scalastyle:off cyclomatic.complexity
  private def processUpdate(app: ApplicationData, command: ApplicationCommand)(implicit hc: HeaderCarrier): ResultT = {
    command match {
      case cmd: AddClientSecret                                       => addClientSecretCommandHandler.process(app, cmd)
      case cmd: RemoveClientSecret                                    => removeClientSecretCommandHandler.process(app, cmd)
      // case cmd: ChangeProductionApplicationName                       => changeProductionApplicationNameCmdHdlr.process(app, cmd)
      // case cmd: ChangeProductionApplicationPrivacyPolicyLocation      => changeProductionApplicationPrivacyPolicyLocationCmdHdlr.process(app, cmd)
      // case cmd: ChangeProductionApplicationTermsAndConditionsLocation => changeProductionApplicationTermsAndConditionsLocationCmdHdlr.process(app, cmd)
      // case cmd: ChangeResponsibleIndividualToSelf                     => changeResponsibleIndividualToSelfCommandHandler.process(app, cmd)
      // case cmd: ChangeResponsibleIndividualToOther                    => changeResponsibleIndividualToOtherCommandHandler.process(app, cmd)
      // case cmd: VerifyResponsibleIndividual                           => verifyResponsibleIndividualCommandHandler.process(app, cmd)
      // case cmd: DeclineResponsibleIndividual                          => declineResponsibleIndividualCommandHandler.process(app, cmd)
      // case cmd: DeclineResponsibleIndividualDidNotVerify              => declineResponsibleIndividualDidNotVerifyCommandHandler.process(app, cmd)
      // case cmd: DeclineApplicationApprovalRequest                     => declineApplicationApprovalRequestCommandHandler.process(app, cmd)
      // case cmd: DeleteApplicationByCollaborator                       => deleteApplicationByCollaboratorCommandHandler.process(app, cmd)
      // case cmd: DeleteApplicationByGatekeeper                         => deleteApplicationByGatekeeperCommandHandler.process(app, cmd)
      // case cmd: DeleteUnusedApplication                               => deleteUnusedApplicationCommandHandler.process(app, cmd)
      // case cmd: DeleteProductionCredentialsApplication                => deleteProductionCredentialsApplicationCommandHandler.process(app, cmd)
      // case cmd: AddCollaborator                                       => addCollaboratorCommandHandler.process(app, cmd)
      // case cmd: RemoveCollaborator                                    => removeCollaboratorCommandHandler.process(app, cmd)
      // case cmd: SubscribeToApi                                        => subscribeToApiCommandHandler.process(app, cmd)
      // case cmd: UnsubscribeFromApi                                    => unsubscribeFromApiCommandHandler.process(app, cmd)
      case cmd: UpdateRedirectUris                                    => updateRedirectUrisCommandHandler.process(app, cmd)
      case _                                                          => E.fromEither(Left(NonEmptyChain(s"Unknown ApplicationCommand type $command")))
    }
  }
  // scalastyle:on cyclomatic.complexity
}
