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
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ClientSecret, RemoveClientSecret, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RemoveClientSecretCommandHandler @Inject()()(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._
  
  private def validate(app: ApplicationData, cmd: RemoveClientSecret): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map2(isAdminIfInProduction(cmd.actor, app),
      clientSecretExists(cmd.clientSecretId, app))
    { case _ => app }
  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: RemoveClientSecret): NonEmptyList[UpdateApplicationEvent] = {
    val clientSecret: Option[ClientSecret] = app.tokens.production.clientSecrets.find(_.id == cmd.clientSecretId)
    NonEmptyList.of(
      ClientSecretRemoved(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        clientSecretId = cmd.clientSecretId,
        clientSecretName = clientSecret.map(_.name).getOrElse("")
      )
    )
  }

  def process(app: ApplicationData, cmd: RemoveClientSecret): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}
