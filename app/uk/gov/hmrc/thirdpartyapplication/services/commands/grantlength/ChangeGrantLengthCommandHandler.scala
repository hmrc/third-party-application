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

package uk.gov.hmrc.thirdpartyapplication.services.commands.grantlength

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats._
import cats.data._
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeGrantLength
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ChangeGrantLengthCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: StoredApplication, cmd: ChangeGrantLength): Validated[Failures, Unit] = {
    Apply[Validated[Failures, *]].map(
      cond(
        (cmd.grantLength.period != app.refreshTokensAvailableFor),
        CommandFailures.GenericFailure(s"Grant length is already ${app.refreshTokensAvailableFor}")
      )
    ) { case _ => () }
  }

  private def asEvents(app: StoredApplication, cmd: ChangeGrantLength): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.GrantLengthChanged(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
        oldGrantLengthInDays = app.refreshTokensAvailableFor.getDays,
        newGrantLengthInDays = cmd.grantLength.period.getDays
      )
    )
  }

  def process(app: StoredApplication, cmd: ChangeGrantLength): AppCmdResultT = {

    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.updateApplicationGrantLength(app.id, cmd.grantLength.period))
      events    = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
