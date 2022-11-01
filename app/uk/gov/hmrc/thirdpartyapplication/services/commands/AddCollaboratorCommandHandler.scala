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
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AddCollaboratorCommandHandler @Inject()()(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._


  import UpdateApplicationEvent._

   private def asEvents(app: ApplicationData, cmd: AddCollaborator): NonEmptyList[UpdateApplicationEvent] ={
    asEvents(app, getRequester(app, cmd.instigator), cmd.adminsToEmail, CollaboratorActor(cmd.email), cmd.timestamp, cmd.collaborator)
  }

  private def asEvents(app: ApplicationData, cmd: AddCollaboratorGatekeeper): NonEmptyList[UpdateApplicationEvent] = {
    asEvents(app, cmd.gatekeeperUser, cmd.adminsToEmail, GatekeeperUserActor(cmd.gatekeeperUser), cmd.timestamp, cmd.collaborator)
  }

  private def asEvents(app: ApplicationData, requestingAdminEmail: String, adminsToEmail:Set[String], actor: Actor, eventTime: LocalDateTime, collaborator: Collaborator): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      CollaboratorAdded(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = eventTime,
        actor = actor,
        collaboratorId = collaborator.userId,
        collaboratorEmail = collaborator.emailAddress.toLowerCase,
        collaboratorRole = collaborator.role,
        verifiedAdminsToEmail = adminsToEmail,
        requestingAdminEmail = requestingAdminEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: AddCollaborator): CommandHandler.Result = {
    Future.successful {
      Apply[ValidatedNec[String, *]]
        .map2(instigatorIsCollaboratorOnApp(cmd.instigator, app), collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app)){ case _ => app } map { _ =>
        asEvents(app, cmd)
      }
    }
  }

  def process(app: ApplicationData, cmd: AddCollaboratorGatekeeper): CommandHandler.Result = {
    Future.successful {
     Apply[ValidatedNec[String, *]].map(collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app)){ case _ => app } map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}
