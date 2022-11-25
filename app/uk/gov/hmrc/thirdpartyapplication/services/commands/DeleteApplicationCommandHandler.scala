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
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteApplication, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class DeleteApplicationCommandHandler @Inject()(
    val authControlConfig: AuthControlConfig,
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import UpdateApplicationEvent._

  def canDeleteApplications =
    cond(authControlConfig.canDeleteApplications, "Cannot delete this applicaton")

  def matchesAuthorisationKey =
    // TODO - need to pass in the request to get request.matchesAuthorisationKey
    cond(true, "Cannot delete this applicaton")

  private def validate(app: ApplicationData, cmd: DeleteApplication): ValidatedNec[String, ApplicationData] = {
    cmd.actor match {
      case CollaboratorActor(actorEmail: String) =>  Apply[ValidatedNec[String, *]]
        .map4(isCollaboratorOnApp(actorEmail, app),
              isStandardAccess(app),
              canDeleteApplications,
              matchesAuthorisationKey){case _ => app}
      case _ => Apply[ValidatedNec[String, *]] 
        .map(isStandardAccess(app)){case _ => app}
    }
  }

  private def asEvents(app: ApplicationData, cmd: DeleteApplication): NonEmptyList[UpdateApplicationEvent] = {
    val requestingAdminEmail = cmd.instigator.toString()
    val clientId = app.tokens.production.clientId
    NonEmptyList.of(
      ApplicationDeleted(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons,
        requestingAdminEmail = requestingAdminEmail
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        app.state.name,
        State.DELETED,
        requestingAdminName = requestingAdminEmail,
        requestingAdminEmail = requestingAdminEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: DeleteApplication): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}
