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

package uk.gov.hmrc.thirdpartyapplication.services.commands.collaborator

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats._
import cats.data._
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.AddCollaborator
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, ApplicationEvents, EventId}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class AddCollaboratorCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: StoredApplication, cmd: AddCollaborator): Validated[Failures, Unit] = {
    cmd.actor match {
      case actor: Actors.AppCollaborator => Apply[Validated[Failures, *]].map2(
          isAppActorACollaboratorOnApp(actor, app),
          collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app)
        ) { (_, _) => () }
      case _                             => Apply[Validated[Failures, *]]
          .map(collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app))(_ => ())
    }
  }

  private def asEvents(app: StoredApplication, cmd: AddCollaborator): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.CollaboratorAddedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        collaborator = cmd.collaborator
      )
    )
  }

  def process(app: StoredApplication, cmd: AddCollaborator): AppCmdResultT = {
    for {
      _        <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.addCollaborator(app.id, cmd.collaborator))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
