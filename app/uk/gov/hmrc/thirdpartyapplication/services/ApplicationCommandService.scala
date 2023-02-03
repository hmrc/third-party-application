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

import cats.data.{EitherT, NonEmptyChain, Validated}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService

@Singleton
class ApplicationCommandService @Inject() (
    applicationRepository: ApplicationRepository,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    stateHistoryRepository: StateHistoryRepository,
    notificationRepository: NotificationRepository,
    notificationService: NotificationService,
    apiPlatformEventService: ApiPlatformEventService,
    submissionService: SubmissionsService,
    thirdPartyDelegatedAuthorityService: ThirdPartyDelegatedAuthorityService,
    apiGatewayStore: ApiGatewayStore,
    auditService: AuditService,
    // changeProductionApplicationPrivacyPolicyLocationCmdHdlr: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
    // changeProductionApplicationTermsAndConditionsLocationCmdHdlr: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
    // changeResponsibleIndividualToSelfCommandHandler: ChangeResponsibleIndividualToSelfCommandHandler,
    changeResponsibleIndividualToOtherCommandHandler: ChangeResponsibleIndividualToOtherCommandHandler
    // verifyResponsibleIndividualCommandHandler: VerifyResponsibleIndividualCommandHandler,
    // declineApplicationApprovalRequestCommandHandler: DeclineApplicationApprovalRequestCommandHandler
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {
  import cats.implicits._
  private val E = EitherTHelper.make[NonEmptyChain[String]]

  def update(applicationId: ApplicationId, command: ApplicationCommand)(implicit hc: HeaderCarrier): EitherT[Future, NonEmptyChain[String], ApplicationData] = {
    for {
      app    <- E.fromOptionF(applicationRepository.fetch(applicationId), NonEmptyChain(s"No application found with id $applicationId"))
      events <- EitherT(processUpdate(app, command).map(_.toEither))

      savedApp <- E.liftF(applicationRepository.applyEvents(events))
      _        <- E.liftF(stateHistoryRepository.applyEvents(events))
      _        <- E.liftF(submissionService.applyEvents(events))
      _        <- E.liftF(thirdPartyDelegatedAuthorityService.applyEvents(events))
      _        <- E.liftF(apiGatewayStore.applyEvents(events))
      _        <- E.liftF(responsibleIndividualVerificationRepository.applyEvents(events))
      _        <- E.liftF(notificationRepository.applyEvents(events))

      _ <- E.liftF(apiPlatformEventService.applyEvents(events))
      _ <- E.liftF(auditService.applyEvents(savedApp, events))
      _ <- E.liftF(notificationService.sendNotifications(savedApp, events.collect { case evt: UpdateApplicationEvent with TriggersNotification => evt }))
    } yield savedApp
  }

  // scalastyle:off cyclomatic.complexity
  private def processUpdate(app: ApplicationData, command: ApplicationCommand)(implicit hc: HeaderCarrier): CommandHandler.Result = {
    command match {
      case cmd: AddClientSecret                                       => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case cmd: RemoveClientSecret                                    => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // removeClientSecretCommandHandler.process(app, cmd)
      case cmd: ChangeProductionApplicationName                       => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // changeProductionApplicationNameCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationPrivacyPolicyLocation      => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // changeProductionApplicationPrivacyPolicyLocationCmdHdlr.process(app, cmd)
      case cmd: ChangeProductionApplicationTermsAndConditionsLocation => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // changeProductionApplicationTermsAndConditionsLocationCmdHdlr.process(app, cmd)
      case cmd: ChangeResponsibleIndividualToSelf                     => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // changeResponsibleIndividualToSelfCommandHandler.process(app, cmd)
      case cmd: ChangeResponsibleIndividualToOther                    => changeResponsibleIndividualToOtherCommandHandler.process(app, cmd)
      case cmd: VerifyResponsibleIndividual                           => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // verifyResponsibleIndividualCommandHandler.process(app, cmd)
      case cmd: DeclineResponsibleIndividual                          => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // declineResponsibleIndividualCommandHandler.process(app, cmd)
      case cmd: DeclineResponsibleIndividualDidNotVerify              => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // declineResponsibleIndividualDidNotVerifyCommandHandler.process(app, cmd)
      case cmd: DeclineApplicationApprovalRequest                     => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // declineApplicationApprovalRequestCommandHandler.process(app, cmd)
      case cmd: DeleteApplicationByCollaborator                       => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case cmd: DeleteApplicationByGatekeeper                         => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case cmd: DeleteUnusedApplication                               => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case cmd: DeleteProductionCredentialsApplication                => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case cmd: AddCollaborator                                       => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // addCollaboratorCommandHandler.process(app, cmd)
      case cmd: RemoveCollaborator                                    => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command")) // add
      case cmd: SubscribeToApi                                        => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case cmd: UnsubscribeFromApi                                    => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case cmd: UpdateRedirectUris                                    => Future.successful(Validated.invalidNec(s"Unsupported ApplicationCommand type $command"))
      case _                                                          => Future.successful(Validated.invalidNec(s"Unknown ApplicationCommand type $command"))
    }
  }
  // scalastyle:on cyclomatic.complexity
}
