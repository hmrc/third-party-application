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

package uk.gov.hmrc.thirdpartyapplication.services.commands.delete

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{Collaborator, DeleteRestriction, State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeleteApplicationByCollaborator
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, ThirdPartyDelegatedAuthorityService}

@Singleton
class DeleteApplicationByCollaboratorCommandHandler @Inject() (
    val authControlConfig: AuthControlConfig,
    val applicationRepository: ApplicationRepository,
    val apiGatewayStore: ApiGatewayStore,
    val notificationRepository: NotificationRepository,
    val responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    val thirdPartyDelegatedAuthorityService: ThirdPartyDelegatedAuthorityService,
    val stateHistoryRepository: StateHistoryRepository,
    val termsOfUseInvitationRepository: TermsOfUseInvitationRepository
  )(implicit val ec: ExecutionContext
  ) extends AbstractDeleteApplicationCommandHandler {

  import CommandHandler._

  private def canDeleteApplicationsOrNotProductionApp(app: StoredApplication) =
    cond(authControlConfig.canDeleteApplications || !app.isInPreProductionOrProduction, "Cannot delete this applicaton")

  private def canDeleteApplication(app: StoredApplication) =
    cond(app.deleteRestriction == DeleteRestriction.NoRestriction, "This application is delete restricted")

  private def validate(app: StoredApplication, cmd: DeleteApplicationByCollaborator): Validated[Failures, Collaborator] = {
    Apply[Validated[Failures, *]].map4(
      isAdminOnApp(cmd.instigator, app),
      ensureStandardAccess(app),
      canDeleteApplicationsOrNotProductionApp(app),
      canDeleteApplication(app)
    ) { case (admin, _, _, _) => admin }
  }

  private def asEvents(app: StoredApplication, cmd: DeleteApplicationByCollaborator, instigator: Collaborator, stateHistory: StateHistory): NonEmptyList[ApplicationEvent] = {
    val clientId       = app.tokens.production.clientId
    val requesterEmail = instigator.emailAddress
    NonEmptyList.of(
      ApplicationEvents.ApplicationDeleted(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.AppCollaborator(requesterEmail),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      fromStateHistory(stateHistory, requesterEmail.text, requesterEmail)
    )
  }

  def process(app: StoredApplication, cmd: DeleteApplicationByCollaborator)(implicit hc: HeaderCarrier): AppCmdResultT = {
    for {
      instigator          <- E.fromEither(validate(app, cmd).toEither)
      kindOfRequesterEmail = instigator.emailAddress.text
      savedApp            <- E.liftF(applicationRepository.updateApplicationState(app.id, State.DELETED, cmd.timestamp, kindOfRequesterEmail, kindOfRequesterEmail))
      stateHistory         = StateHistory(app.id, State.DELETED, Actors.AppCollaborator(instigator.emailAddress), Some(app.state.name), changedAt = cmd.timestamp)
      _                   <- deleteApplication(app, stateHistory)
      events               = asEvents(savedApp, cmd, instigator, stateHistory)
    } yield (savedApp, events)
  }
}
