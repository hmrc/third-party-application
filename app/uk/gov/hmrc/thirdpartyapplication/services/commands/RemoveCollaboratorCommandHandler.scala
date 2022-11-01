/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.Apply
import cats.data.{NonEmptyList, ValidatedNec}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ActorType, Collaborator, RemoveCollaborator, RemoveCollaboratorGateKeeper, RemoveCollaboratorPlatformJobs, UpdateApplicationEvent, UserId}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RemoveCollaboratorCommandHandler @Inject()()(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._


  private def validate(app: ApplicationData, collaboratorEmail: String): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map(applicationWillHaveAnAdmin(collaboratorEmail, app))(_ => app)
  }

  private def validateWithInstigator(app: ApplicationData, collaboratorEmail: String, instigatorId: UserId): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map2(instigatorIsCollaboratorOnApp(instigatorId, app),applicationWillHaveAnAdmin(collaboratorEmail, app)){case _ => app}
  }


  import UpdateApplicationEvent._


   private def asEvents(app: ApplicationData, cmd: RemoveCollaborator): NonEmptyList[UpdateApplicationEvent] ={
    asEvents(app, getRequester(app, cmd.instigator), cmd.adminsToEmail, CollaboratorActor(cmd.email), cmd.timestamp, cmd.collaborator, notifyCollaborator = true)
  }

  private def asEvents(app: ApplicationData, cmd: RemoveCollaboratorGateKeeper): NonEmptyList[UpdateApplicationEvent] = {
    asEvents(app, cmd.gatekeeperUser, cmd.adminsToEmail, GatekeeperUserActor(cmd.gatekeeperUser), cmd.timestamp, cmd.collaborator, notifyCollaborator = true)
  }

  private def asEvents(app: ApplicationData, cmd: RemoveCollaboratorPlatformJobs): NonEmptyList[UpdateApplicationEvent] = {
    asEvents(app, ActorType.SCHEDULED_JOB.toString , cmd.adminsToEmail, ScheduledJobActor(cmd.jobId), cmd.timestamp, cmd.collaborator, notifyCollaborator = false)
  }


  private def asEvents(app: ApplicationData,
                       requestingAdminEmail: String,
                       adminsToEmail:Set[String],
                       actor: Actor,
                       eventTime: LocalDateTime,
                       collaborator: Collaborator,
                       notifyCollaborator: Boolean): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      CollaboratorRemoved(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = eventTime,
        actor = actor,
        collaboratorId = collaborator.userId,
        collaboratorEmail = collaborator.emailAddress,
        collaboratorRole = collaborator.role,
        notifyCollaborator = notifyCollaborator,
        verifiedAdminsToEmail = adminsToEmail,
        requestingAdminEmail = requestingAdminEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: RemoveCollaborator): CommandHandler.Result = {
    Future.successful {
      validateWithInstigator(app, cmd.collaborator.emailAddress, cmd.instigator) map { _ =>
        asEvents(app, cmd)
      }
    }
  }

  def process(app: ApplicationData, cmd: RemoveCollaboratorGateKeeper): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd.collaborator.emailAddress) map { _ =>
        asEvents(app, cmd)
      }
    }
  }

  def process(app: ApplicationData, cmd: RemoveCollaboratorPlatformJobs): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd.collaborator.emailAddress) map { _ =>
        asEvents(app, cmd)
      }
    }
  }

}
