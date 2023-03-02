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

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteProductionCredentialsApplication, State, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler.ResultT
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, ThirdPartyDelegatedAuthorityService}

@Singleton
class DeleteProductionCredentialsApplicationCommandHandler @Inject() (
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

  private def validate(app: ApplicationData): Validated[CommandHandler.Failures, ApplicationData] = {
    Apply[Validated[CommandHandler.Failures, *]]
      .map(isInTesting(app)) { case _ => app }
  }

  private def asEvents(app: ApplicationData, cmd: DeleteProductionCredentialsApplication, stateHistory: StateHistory): NonEmptyList[ApplicationEvent] = {
    val clientId = app.tokens.production.clientId
    NonEmptyList.of(
      ProductionCredentialsApplicationDeleted(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = Actors.ScheduledJob(cmd.jobId),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      fromStateHistory(stateHistory, cmd.jobId, LaxEmailAddress(cmd.jobId))
    )
  }

  def process(app: ApplicationData, cmd: DeleteProductionCredentialsApplication)(implicit hc: HeaderCarrier): ResultT = {
    for {
      valid       <- E.fromEither(validate(app).toEither)
      savedApp    <- E.liftF(applicationRepository.updateApplicationState(app.id, State.DELETED, cmd.timestamp, cmd.jobId, cmd.jobId))
      stateHistory = StateHistory(app.id, State.DELETED, Actors.ScheduledJob(cmd.jobId), Some(app.state.name), changedAt = cmd.timestamp)
      events       = asEvents(savedApp, cmd, stateHistory)
      _           <- deleteApplication(app, stateHistory)
    } yield (savedApp, events)
  }

}
