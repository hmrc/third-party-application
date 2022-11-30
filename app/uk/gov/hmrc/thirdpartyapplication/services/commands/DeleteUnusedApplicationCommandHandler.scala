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
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteUnusedApplication, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class DeleteUnusedApplicationCommandHandler @Inject()(
    val authControlConfig: AuthControlConfig,
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import UpdateApplicationEvent._

  def matchesAuthorisationKey(cmd: DeleteUnusedApplication) =
    cond(authControlConfig.authorisationKey == cmd.authorisationKey, "Cannot delete this applicaton")

  private def validate(app: ApplicationData, cmd: DeleteUnusedApplication): ValidatedNec[String, ApplicationData] = {
    cmd.actor match {
      case ScheduledJobActor(jobId: String) =>  Apply[ValidatedNec[String, *]]
        .map(matchesAuthorisationKey(cmd)){case _ => app}
      case _ => Validated.invalidNec("Invalid actor type")
    }
  }

  private def asEvents(app: ApplicationData, cmd: DeleteUnusedApplication): NonEmptyList[UpdateApplicationEvent] = {
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
        requestingAdminEmail = None
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

  def process(app: ApplicationData, cmd: DeleteUnusedApplication): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}
