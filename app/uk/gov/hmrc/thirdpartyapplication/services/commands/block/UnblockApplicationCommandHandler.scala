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

package uk.gov.hmrc.thirdpartyapplication.services.commands.block

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UnblockApplication
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class UnblockApplicationCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler with ApplicationLogger with ClockNow {

  import CommandHandler._

  private def validate(app: StoredApplication): Validated[Failures, StoredApplication] = {
    Validated.validNel(app)
  }

  private def asEvents(
      app: StoredApplication,
      cmd: UnblockApplication
    ): NonEmptyList[ApplicationEvent] = {

    NonEmptyList.of(ApplicationUnblocked(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = Actors.GatekeeperUser(cmd.gatekeeperUser)
    ))
  }

  def process(app: StoredApplication, cmd: UnblockApplication): AppCmdResultT = {
    def unblock(application: StoredApplication): StoredApplication = {
      application.copy(blocked = false)
    }

    for {
      valid <- E.fromEither(validate(app).toEither)
      _     <- E.liftF(applicationRepository.save(unblock(app)))
      events = asEvents(app, cmd)
    } yield (app, events)
  }
}
