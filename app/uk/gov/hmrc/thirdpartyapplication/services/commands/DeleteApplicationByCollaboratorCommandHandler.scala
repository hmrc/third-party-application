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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, ValidatedNec}

import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteApplicationByCollaborator, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class DeleteApplicationByCollaboratorCommandHandler @Inject()(
    val authControlConfig: AuthControlConfig
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import UpdateApplicationEvent._

  def canDeleteApplicationsOrNotProductionApp(app: ApplicationData) =
    cond(authControlConfig.canDeleteApplications || !app.state.isInPreProductionOrProduction, "Cannot delete this applicaton")

  private def validate(app: ApplicationData, cmd: DeleteApplicationByCollaborator): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]]
        .map3(isAdminOnApp(cmd.instigator, app),
              isStandardAccess(app),
              canDeleteApplicationsOrNotProductionApp(app)){case _ => app}
  }

  private def asEvents(app: ApplicationData, cmd: DeleteApplicationByCollaborator): NonEmptyList[UpdateApplicationEvent] = {
    val clientId = app.tokens.production.clientId
    val requesterEmail = getRequester(app, cmd.instigator)
    NonEmptyList.of(
      ApplicationDeleted(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        app.state.name,
        State.DELETED,
        requestingAdminName = requesterEmail,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: DeleteApplicationByCollaborator): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}
