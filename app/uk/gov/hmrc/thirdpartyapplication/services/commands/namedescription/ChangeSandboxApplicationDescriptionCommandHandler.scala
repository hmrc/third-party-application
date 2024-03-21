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

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeSandboxApplicationDescription
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ChangeSandboxApplicationDescriptionCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(
      app: StoredApplication,
      cmd: ChangeSandboxApplicationDescription
    ): Validated[Failures, StoredApplication] = {
    Apply[Validated[Failures, *]].map5(
      isInSandboxEnvironment(app),
      isApproved(app),
      isAppActorACollaboratorOnApp(cmd.actor, app),
      cond(cmd.description.isBlank() == false, "Description cannot be blank when changing the description"),
      cond(app.description != Some(cmd.description).filterNot(_.isBlank()), "App already has that description")
    ) { case _ => app }
  }

  private def asEvents(app: StoredApplication, cmd: ChangeSandboxApplicationDescription): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.SandboxApplicationDescriptionChanged(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        oldDescription = app.description,
        description = cmd.description
      )
    )
  }

  def process(app: StoredApplication, cmd: ChangeSandboxApplicationDescription): AppCmdResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.updateDescription(app.id, Some(cmd.description)))
      events    = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
