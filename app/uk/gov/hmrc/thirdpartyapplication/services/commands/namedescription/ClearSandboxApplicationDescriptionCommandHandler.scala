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

package uk.gov.hmrc.thirdpartyapplication.services.commands.namedescription

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats._
import cats.data._
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ClearSandboxApplicationDescription
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ClearSandboxApplicationDescriptionCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(
      app: StoredApplication,
      cmd: ClearSandboxApplicationDescription
    ): Validated[Failures, String] = {
    Apply[Validated[Failures, *]].map3(
      isInSandboxEnvironment(app),
      isApproved(app),
      isAppActorACollaboratorOnApp(cmd.actor, app)
    ) { case _ => () }
      .andThen(_ =>
        mustBeDefined(app.description, "App does not currently have a description to clear")
      )
  }

  private def asEvents(app: StoredApplication, oldDescription: String, cmd: ClearSandboxApplicationDescription): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.SandboxApplicationDescriptionCleared(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        oldDescription
      )
    )
  }

  def process(app: StoredApplication, cmd: ClearSandboxApplicationDescription): AppCmdResultT = {
    for {
      oldDescription <- E.fromEither(validate(app, cmd).toEither)
      savedApp       <- E.liftF(applicationRepository.updateDescription(app.id, None))
      events          = asEvents(app, oldDescription, cmd)
    } yield (savedApp, events)
  }
}
