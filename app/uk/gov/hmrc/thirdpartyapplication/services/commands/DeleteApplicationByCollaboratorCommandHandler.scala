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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import cats.Apply
import cats.data.{NonEmptyList, Validated}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteApplicationByCollaborator, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, ThirdPartyDelegatedAuthorityService}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Collaborator

@Singleton
class DeleteApplicationByCollaboratorCommandHandler @Inject() (
    val authControlConfig: AuthControlConfig,
    val applicationRepository: ApplicationRepository,
    val apiGatewayStore: ApiGatewayStore,
    val notificationRepository: NotificationRepository,
    val responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    val thirdPartyDelegatedAuthorityService: ThirdPartyDelegatedAuthorityService,
    val stateHistoryRepository: StateHistoryRepository
  )(implicit val ec: ExecutionContext
  ) extends DeleteApplicationCommandHandler {

  import CommandHandler2._
  import UpdateApplicationEvent._

  def canDeleteApplicationsOrNotProductionApp(app: ApplicationData) =
    cond(authControlConfig.canDeleteApplications || !app.state.isInPreProductionOrProduction, "Cannot delete this applicaton")

  private def validate(app: ApplicationData, cmd: DeleteApplicationByCollaborator): Validated[CommandFailures, Collaborator] = {
    Apply[Validated[CommandFailures, *]].map3(
      isAdminOnApp(cmd.instigator, app),
      isStandardAccess(app),
      canDeleteApplicationsOrNotProductionApp(app)
    ) { case (admin, _, _) => admin }
  }

  private def asEvents(app: ApplicationData, cmd: DeleteApplicationByCollaborator, instigator: Collaborator): NonEmptyList[UpdateApplicationEvent] = {
    val clientId       = app.tokens.production.clientId
    val requesterEmail = instigator.emailAddress
    NonEmptyList.of(
      ApplicationDeleted(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        app.state.name,
        State.DELETED,
        requestingAdminName = requesterEmail,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: DeleteApplicationByCollaborator)(implicit hc: HeaderCarrier): ResultT = {
    for {
      instigator <- E.fromEither(validate(app, cmd).toEither)
      savedApp   <- E.liftF(applicationRepository.updateApplicationState(app.id, State.DELETED, cmd.timestamp, instigator.emailAddress, instigator.emailAddress))
      events      = asEvents(savedApp, cmd, instigator)
      _          <- deleteApplication(app, cmd.timestamp, instigator.emailAddress, instigator.emailAddress, events)
    } yield (savedApp, events)
  }
}
