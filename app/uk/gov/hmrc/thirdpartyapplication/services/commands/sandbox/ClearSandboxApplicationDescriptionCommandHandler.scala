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

package uk.gov.hmrc.thirdpartyapplication.services.commands.sandbox

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
    ): Validated[Failures, StoredApplication] = {
    Apply[Validated[Failures, *]].map4(
      isInSandboxEnvironment(app),
      isApproved(app),
      isAppActorACollaboratorOnApp(cmd.actor, app),
      cond(app.description.isDefined, "App does not currently have a description to clear")
    ) { case _ => app }
  }

  private def asEvents(app: StoredApplication, cmd: ClearSandboxApplicationDescription): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.SandboxApplicationDescriptionCleared(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        oldDescription = app.description.get // This is guarded by the validate function
      )
    )
  }

  def process(app: StoredApplication, cmd: ClearSandboxApplicationDescription): AppCmdResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.updateDescription(app.id, None))
      events    = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
