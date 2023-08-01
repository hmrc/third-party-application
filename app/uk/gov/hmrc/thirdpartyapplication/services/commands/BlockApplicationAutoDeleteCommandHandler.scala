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

import cats.data._
import cats.implicits._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.{AllowApplicationAutoDelete, BlockApplicationAutoDelete}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class BlockApplicationAutoDeleteCommandHandler @Inject()(
                                                          applicationRepository: ApplicationRepository
                                                        )(implicit val ec: ExecutionContext
                                                        ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData): Validated[Failures, ApplicationData] = {
    Validated.validNel(app)
  }

  private def asEvents(app: ApplicationData, cmd: BlockApplicationAutoDelete): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.BlockApplicationAutoDelete(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser)
      )
    )
  }

  def process(app: ApplicationData, cmd: BlockApplicationAutoDelete): AppCmdResultT = {

    for {
      valid <- E.fromEither(validate(app).toEither)
      savedApp <- E.liftF(applicationRepository.updateAllowAutoDelete(app.id, false))
      events = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
