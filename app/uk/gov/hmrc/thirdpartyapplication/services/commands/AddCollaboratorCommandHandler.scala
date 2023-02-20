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

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats._
import cats.data._
import cats.implicits._

import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._

@Singleton
class AddCollaboratorCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: AddCollaborator): Validated[CommandFailures, Unit] = {
    cmd.actor match {
      case Actors.AppCollaborator(actorEmail: LaxEmailAddress) => Apply[Validated[CommandFailures, *]].map2(
          isCollaboratorOnApp(actorEmail, app),
          collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app)
        ) { case _ => () }
      case _                                       => Apply[Validated[CommandFailures, *]]
          .map(collaboratorAlreadyOnApp(cmd.collaborator.emailAddress, app))(_ => ())
    }
  }

  private def asEvents(app: ApplicationData, cmd: AddCollaborator): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      CollaboratorAddedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = cmd.actor,
        collaborator = cmd.collaborator,
        verifiedAdminsToEmail = cmd.adminsToEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: AddCollaborator): ResultT = {
    for {
      _        <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.addCollaborator(app.id, cmd.collaborator))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
