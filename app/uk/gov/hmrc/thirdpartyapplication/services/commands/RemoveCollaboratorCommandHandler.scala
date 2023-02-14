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

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.thirdpartyapplication.domain.models.{RemoveCollaborator, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler.ResultT
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator

@Singleton
class RemoveCollaboratorCommandHandler @Inject() (applicationRepository: ApplicationRepository)(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: RemoveCollaborator) = {

    cmd.actor match {
      case Actors.AppCollaborator(actorEmail) => Apply[Validated[CommandFailures, *]]
          .map3(
            isCollaboratorOnApp(actorEmail, app),
            isCollaboratorOnApp(cmd.collaborator.emailAddress, app),
            applicationWillHaveAnAdmin(cmd.collaborator.emailAddress, app)
          ) { case _ => app }
      case _                                       => Apply[Validated[CommandFailures, *]]
          .map2(isCollaboratorOnApp(cmd.collaborator.emailAddress, app), applicationWillHaveAnAdmin(cmd.collaborator.emailAddress, app)) { case _ => app }
    }

  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: RemoveCollaborator): NonEmptyList[UpdateApplicationEvent] = {
    asEvents(app, cmd.actor, cmd.adminsToEmail, cmd.timestamp, cmd.collaborator)
  }

  private def asEvents(app: ApplicationData, actor: Actor, adminsToEmail: Set[LaxEmailAddress], eventTime: LocalDateTime, collaborator: Collaborator): NonEmptyList[UpdateApplicationEvent] = {
    def notifyCollaborator() = {
      actor match {
        case _: Actors.ScheduledJob => false
        case _                      => true
      }
    }

    NonEmptyList.of(
      CollaboratorRemoved(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = eventTime,
        actor = actor,
        collaborator,
        notifyCollaborator = notifyCollaborator(),
        verifiedAdminsToEmail = adminsToEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: RemoveCollaborator): ResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.removeCollaborator(app.id, cmd.collaborator.userId))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
