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
import scala.concurrent.{ExecutionContext, Future}
import cats.Apply
import cats.data.{NonEmptyList, ValidatedNec}
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.CollaboratorActor
import uk.gov.hmrc.thirdpartyapplication.domain.models.{Collaborator, RemoveCollaborator, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler2.ResultT

@Singleton
class RemoveCollaboratorCommandHandler @Inject() (applicationRepository: ApplicationRepository)
                                                 (implicit val ec: ExecutionContext) extends CommandHandler2 {

  import CommandHandler2._

  private def validate(app: ApplicationData, cmd: RemoveCollaborator) = {

    cmd.actor match {
      case CollaboratorActor(actorEmail: String) => Apply[ValidatedNec[String, *]]
          .map3(
            isCollaboratorOnApp(actorEmail, app),
            isCollaboratorOnApp(cmd.collaborator.emailAddress, app),
            applicationWillHaveAnAdmin(cmd.collaborator.emailAddress, app)
          ) { case _ => app }
      case _                                     => Apply[ValidatedNec[String, *]]
          .map2(isCollaboratorOnApp(cmd.collaborator.emailAddress, app), applicationWillHaveAnAdmin(cmd.collaborator.emailAddress, app)) { case _ => app }
    }

  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: RemoveCollaborator): NonEmptyList[UpdateApplicationEvent] = {
    asEvents(app, cmd.actor, cmd.adminsToEmail, cmd.timestamp, cmd.collaborator)
  }

  private def asEvents(app: ApplicationData, actor: Actor, adminsToEmail: Set[String], eventTime: LocalDateTime, collaborator: Collaborator): NonEmptyList[UpdateApplicationEvent] = {
    def notifyCollaborator() = {
      actor match {
        case _: ScheduledJobActor => false
        case _                    => true
      }
    }

    NonEmptyList.of(
      CollaboratorRemoved(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = eventTime,
        actor = actor,
        collaboratorId = collaborator.userId,
        collaboratorEmail = collaborator.emailAddress,
        collaboratorRole = collaborator.role,
        notifyCollaborator = notifyCollaborator(),
        verifiedAdminsToEmail = adminsToEmail
      )
    )
  }

//  def process(app: ApplicationData, cmd: RemoveCollaborator): CommandHandler.Result = {
//    Future.successful {
//      validate(app, cmd) map { _ =>
//        asEvents(app, cmd)
//      }
//    }
//  }

  def process(app: ApplicationData, cmd: RemoveCollaborator): ResultT = {
    for {
      valid <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.removeCollaborator(app.id, cmd.collaborator.userId))
      events = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
