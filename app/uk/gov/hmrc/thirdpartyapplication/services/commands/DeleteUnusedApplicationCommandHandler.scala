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

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.util.Try

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteUnusedApplication, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, ThirdPartyDelegatedAuthorityService}

@Singleton
class DeleteUnusedApplicationCommandHandler @Inject() (
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

  def base64Decode(stringToDecode: String): Try[String] = Try(new String(Base64.getDecoder.decode(stringToDecode), StandardCharsets.UTF_8))

  def matchesAuthorisationKey(cmd: DeleteUnusedApplication) =
    cond(base64Decode(cmd.authorisationKey).map(_ == authControlConfig.authorisationKey).getOrElse(false), "Cannot delete this applicaton")

  private def validate(app: ApplicationData, cmd: DeleteUnusedApplication): Validated[CommandFailures, ApplicationData] = {
    Apply[Validated[CommandFailures, *]]
      .map(matchesAuthorisationKey(cmd)) { case _ => app }
  }

  private def asEvents(app: ApplicationData, cmd: DeleteUnusedApplication): NonEmptyList[UpdateApplicationEvent] = {
    val clientId = app.tokens.production.clientId
    NonEmptyList.of(
      ApplicationDeleted(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = ScheduledJobActor(cmd.jobId),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = ScheduledJobActor(cmd.jobId),
        app.state.name,
        State.DELETED,
        requestingAdminName = cmd.jobId,
        requestingAdminEmail = cmd.jobId
      )
    )
  }

  def process(app: ApplicationData, cmd: DeleteUnusedApplication)(implicit hc: HeaderCarrier): ResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.updateApplicationState(app.id, State.DELETED, cmd.timestamp, cmd.jobId, cmd.jobId))
      events    = asEvents(savedApp, cmd)
      _        <- deleteApplication(app, cmd.timestamp, cmd.jobId, cmd.jobId, events)
    } yield (savedApp, events)
  }

}
