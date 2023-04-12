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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RemoveCollaborator
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class RemoveCollaboratorCommandHandler @Inject() (applicationRepository: ApplicationRepository)(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: RemoveCollaborator) = {

    cmd.actor match {
      case actor: Actors.AppCollaborator => Apply[Validated[CommandHandler.Failures, *]]
          .map3(
            isAppActorACollaboratorOnApp(actor, app),
            isCollaboratorOnApp(cmd.collaborator, app),
            applicationWillStillHaveAnAdmin(cmd.collaborator.emailAddress, app)
          ) { case _ => app }
      case _                             => Apply[Validated[CommandHandler.Failures, *]]
          .map2(
            isCollaboratorOnApp(cmd.collaborator, app),
            applicationWillStillHaveAnAdmin(cmd.collaborator.emailAddress, app)
          ) { case _ => app }
    }

  }

  private def asEvents(app: ApplicationData, cmd: RemoveCollaborator): NonEmptyList[ApplicationEvent] = {
    asEvents(app, cmd.actor, cmd.timestamp, cmd.collaborator)
  }

  private def asEvents(app: ApplicationData, actor: Actor, eventTime: LocalDateTime, collaborator: Collaborator): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      CollaboratorRemovedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = eventTime.instant,
        actor = actor,
        collaborator,
        verifiedAdminsToEmail = Set.empty
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
