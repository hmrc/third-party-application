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
import cats.data.{NonEmptyList, ValidatedNec, Validated}

import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteApplicationByCollaborator, State, UpdateApplicationEvent, Environment}
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

  def isApplicationDeployedToSandbox(app: ApplicationData) =
    cond(app.environment == Environment.SANDBOX.toString, "Cannot delete this applicaton - must be Sandbox")
    
  private def validate(app: ApplicationData, cmd: DeleteApplicationByCollaborator): ValidatedNec[String, ApplicationData] = {
    cmd.actor match {
      case CollaboratorActor(actorEmail: String) => Apply[ValidatedNec[String, *]]
        .map4(isAdminOnApp(actorEmail, app),
              isStandardAccess(app),
              isApplicationDeployedToSandbox(app),
              canDeleteApplicationsOrNotProductionApp(app)){case _ => app}
      case _ => Validated.invalidNec("Invalid actor type")
    }
  }

  private def asEvents(app: ApplicationData, cmd: DeleteApplicationByCollaborator): NonEmptyList[UpdateApplicationEvent] = {
    val clientId = app.tokens.production.clientId
    NonEmptyList.of(
      ApplicationDeleted(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        app.state.name,
        State.DELETED,
        requestingAdminName = cmd.actor.toString,
        requestingAdminEmail = cmd.actor.toString
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
