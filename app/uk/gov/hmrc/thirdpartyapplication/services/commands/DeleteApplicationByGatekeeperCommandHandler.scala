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

import cats.data.{NonEmptyList, ValidatedNec, Validated}

import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteApplicationByGatekeeper, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class DeleteApplicationByGatekeeperCommandHandler @Inject()(
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import UpdateApplicationEvent._

  private def validate(app: ApplicationData, cmd: DeleteApplicationByGatekeeper): ValidatedNec[String, ApplicationData] = {
    cmd.actor match {
      case GatekeeperUserActor(user: String) =>  Validated.validNec(app)
      case _ => Validated.invalidNec("Invalid actor type")
    }
  }

  private def asEvents(app: ApplicationData, cmd: DeleteApplicationByGatekeeper): NonEmptyList[UpdateApplicationEvent] = {
    val requesterEmail = cmd.requestedByEmailAddress
    val clientId = app.tokens.production.clientId
    NonEmptyList.of(
      ApplicationDeletedByGatekeeper(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons,
        requestingAdminEmail = requesterEmail
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        app.state.name,
        State.DELETED,
        requestingAdminName = requesterEmail,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: DeleteApplicationByGatekeeper): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}
