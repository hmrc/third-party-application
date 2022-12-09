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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AddCollaboratorCommandHandler @Inject() ()(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._

  import UpdateApplicationEvent._

  private def validate(app: ApplicationData, cmd: AddCollaborator) = {
    cmd.actor match {
      case CollaboratorActor(actorEmail: String) => Apply[ValidatedNec[String, *]].map2(
          isCollaboratorOnApp(actorEmail, app),
          collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app)
        ) { case _ => app }
      case _                                     => Apply[ValidatedNec[String, *]]
          .map(collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app))(_ => app)
    }

  }

  private def asEvents(app: ApplicationData, cmd: AddCollaborator): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      CollaboratorAdded(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        collaboratorId = cmd.collaborator.userId,
        collaboratorEmail = cmd.collaborator.emailAddress.toLowerCase,
        collaboratorRole = cmd.collaborator.role,
        verifiedAdminsToEmail = cmd.adminsToEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: AddCollaborator): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }

}