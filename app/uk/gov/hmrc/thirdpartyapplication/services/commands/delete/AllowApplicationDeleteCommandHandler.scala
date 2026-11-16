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

import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{DeleteRestriction, DeleteRestrictionType}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.AllowApplicationDelete
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.*
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.*
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class AllowApplicationDeleteCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(using val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler.*

  private def validate(app: StoredApplication): Validated[Failures, Unit] = {
    (
      cond((app.deleteRestriction.deleteRestrictionType == DeleteRestrictionType.DoNotDelete), CommandFailures.GenericFailure(s"Delete is already allowed"))
    ).map(_ => ())
  }

  private def asEvents(app: StoredApplication, cmd: AllowApplicationDelete): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.AllowApplicationDelete(
        id = EventId.random,
        applicationId = app.id,
        reasons = cmd.reasons,
        eventDateTime = cmd.timestamp,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser)
      )
    )
  }

  def process(app: StoredApplication, cmd: AllowApplicationDelete): AppCmdResultT = {

    for {
      _        <- E.fromEither(validate(app).toEither)
      savedApp <- E.liftF(applicationRepository.updateDeleteRestriction(app.id, DeleteRestriction.NoRestriction))
      events    = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
