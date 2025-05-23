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

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.RemoveCollaborator
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, EventId}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class RemoveCollaboratorCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: StoredApplication, cmd: RemoveCollaborator) = {

    cmd.actor match {
      case actor: Actors.AppCollaborator => Apply[Validated[Failures, *]]
          .map3(
            isAppActorACollaboratorOnApp(actor, app),
            isCollaboratorOnApp(cmd.collaborator, app),
            applicationWillStillHaveAnAdmin(cmd.collaborator.emailAddress, app)
          ) { case _ => app }
      case _                             => Apply[Validated[Failures, *]]
          .map2(
            isCollaboratorOnApp(cmd.collaborator, app),
            applicationWillStillHaveAnAdmin(cmd.collaborator.emailAddress, app)
          ) { case _ => app }
    }

  }

  private def asEvents(app: StoredApplication, cmd: RemoveCollaborator): NonEmptyList[ApplicationEvent] = {
    asEvents(app, cmd.actor, cmd.timestamp, cmd.collaborator)
  }

  private def asEvents(app: StoredApplication, actor: Actor, eventTime: Instant, collaborator: Collaborator): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      CollaboratorRemovedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = eventTime,
        actor = actor,
        collaborator
      )
    )
  }

  def process(app: StoredApplication, cmd: RemoveCollaborator): AppCmdResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.removeCollaborator(app.id, cmd.collaborator.userId))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
