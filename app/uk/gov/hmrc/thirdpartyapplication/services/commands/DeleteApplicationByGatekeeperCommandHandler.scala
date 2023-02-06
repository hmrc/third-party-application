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
import cats.data.{NonEmptyList, Validated}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteApplicationByGatekeeper, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, ThirdPartyDelegatedAuthorityService}

@Singleton
class DeleteApplicationByGatekeeperCommandHandler @Inject() (
    val authControlConfig: AuthControlConfig,
    val applicationRepository: ApplicationRepository,
    val apiGatewayStore: ApiGatewayStore,
    val notificationRepository: NotificationRepository,
    val responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    val thirdPartyDelegatedAuthorityService: ThirdPartyDelegatedAuthorityService,
    val stateHistoryRepository: StateHistoryRepository
  )(implicit val ec: ExecutionContext
  ) extends DeleteApplicationCommandHandler {

  import CommandHandler._
  import UpdateApplicationEvent._

  private def validate(app: ApplicationData): Validated[CommandFailures, ApplicationData] = {
    Validated.validNec(app)
  }

  private def asEvents(app: ApplicationData, cmd: DeleteApplicationByGatekeeper): NonEmptyList[UpdateApplicationEvent] = {
    val requesterEmail = cmd.requestedByEmailAddress
    val clientId       = app.tokens.production.clientId
    NonEmptyList.of(
      ApplicationDeletedByGatekeeper(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = GatekeeperUserActor(cmd.gatekeeperUser),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons,
        requestingAdminEmail = requesterEmail
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = GatekeeperUserActor(cmd.gatekeeperUser),
        app.state.name,
        State.DELETED,
        requestingAdminName = requesterEmail,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: DeleteApplicationByGatekeeper)(implicit hc: HeaderCarrier): ResultT = {
    for {
      valid    <- E.fromEither(validate(app).toEither)
      events    = asEvents(app, cmd)
      savedApp <- E.liftF(applicationRepository.updateApplicationState(app.id, State.DELETED, cmd.timestamp, cmd.requestedByEmailAddress, cmd.requestedByEmailAddress))
      _        <- deleteApplication(app, cmd.timestamp, cmd.requestedByEmailAddress, cmd.requestedByEmailAddress, events)
    } yield (savedApp, events)
  }
}
