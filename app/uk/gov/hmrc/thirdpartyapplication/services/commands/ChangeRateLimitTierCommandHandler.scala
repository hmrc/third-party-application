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

import cats.data._
import cats.implicits._

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RateLimitTier
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.ApiGatewayStore

@Singleton
class ChangeRateLimitTierCommandHandler @Inject() (
    apiGatewayStore: ApiGatewayStore,
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def asEvents(app: StoredApplication, cmd: ApplicationCommands.ChangeRateLimitTier): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.RateLimitChanged(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
        oldRateLimit = app.rateLimitTier.getOrElse(RateLimitTier.BRONZE),
        newRateLimit = cmd.rateLimitTier
      )
    )
  }

  def process(app: StoredApplication, cmd: ApplicationCommands.ChangeRateLimitTier)(implicit hc: HeaderCarrier): AppCmdResultT = {

    for {
      _        <- E.liftF(apiGatewayStore.updateApplication(app, cmd.rateLimitTier))
      savedApp <- E.liftF(applicationRepository.updateApplicationRateLimit(app.id, cmd.rateLimitTier))
      events    = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
