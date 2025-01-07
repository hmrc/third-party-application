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

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.util.Try

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeleteUnusedApplication
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, ThirdPartyDelegatedAuthorityService}

@Singleton
class DeleteUnusedApplicationCommandHandler @Inject() (
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

  def base64Decode(stringToDecode: String): Try[String] = Try(new String(Base64.getDecoder.decode(stringToDecode), StandardCharsets.UTF_8))

  def matchesAuthorisationKey(cmd: DeleteUnusedApplication) =
    cond(base64Decode(cmd.authorisationKey).map(_ == authControlConfig.authorisationKey).getOrElse(false), "Cannot delete this application")

  private def validate(app: StoredApplication, cmd: DeleteUnusedApplication): Validated[Failures, StoredApplication] = {
    Apply[Validated[Failures, *]]
      .map(matchesAuthorisationKey(cmd)) { case _ => app }
  }

  private def asEvents(app: StoredApplication, cmd: DeleteUnusedApplication, stateHistory: StateHistory): NonEmptyList[ApplicationEvent] = {
    val clientId = app.tokens.production.clientId
    NonEmptyList.of(
      ApplicationEvents.ApplicationDeleted(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.ScheduledJob(cmd.jobId),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      fromStateHistory(stateHistory, cmd.jobId, LaxEmailAddress(cmd.jobId))
    )
  }

  def process(app: StoredApplication, cmd: DeleteUnusedApplication)(implicit hc: HeaderCarrier): AppCmdResultT = {
    for {
      valid       <- E.fromEither(validate(app, cmd).toEither)
      savedApp    <- E.liftF(applicationRepository.updateApplicationState(app.id, State.DELETED, cmd.timestamp, cmd.jobId, cmd.jobId))
      stateHistory = StateHistory(app.id, State.DELETED, Actors.ScheduledJob(cmd.jobId), Some(app.state.name), changedAt = cmd.timestamp)
      events       = asEvents(savedApp, cmd, stateHistory)
      _           <- deleteApplication(app, stateHistory)
    } yield (savedApp, events)
  }

}
