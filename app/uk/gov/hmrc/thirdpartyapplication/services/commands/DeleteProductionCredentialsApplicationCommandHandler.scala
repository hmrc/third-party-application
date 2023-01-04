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
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, ValidatedNec}

import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeleteProductionCredentialsApplication, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class DeleteProductionCredentialsApplicationCommandHandler @Inject()(
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._
  import UpdateApplicationEvent._

  private def validate(app: ApplicationData, cmd: DeleteProductionCredentialsApplication): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]]
        .map(isInTesting(app)){case _ => app}
  }

  private def asEvents(app: ApplicationData, cmd: DeleteProductionCredentialsApplication): NonEmptyList[UpdateApplicationEvent] = {
    val clientId = app.tokens.production.clientId
    NonEmptyList.of(
      ProductionCredentialsApplicationDeleted(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = ScheduledJobActor(cmd.jobId),
        clientId = clientId,
        wso2ApplicationName = app.wso2ApplicationName,
        reasons = cmd.reasons
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = ScheduledJobActor(cmd.jobId),
        app.state.name,
        State.DELETED,
        requestingAdminName = cmd.jobId,
        requestingAdminEmail = cmd.jobId
      )
    )
  }

  def process(app: ApplicationData, cmd: DeleteProductionCredentialsApplication): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}
