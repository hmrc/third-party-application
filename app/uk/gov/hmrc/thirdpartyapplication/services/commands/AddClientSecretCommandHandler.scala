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
import uk.gov.hmrc.thirdpartyapplication.domain.models.{AddClientSecret, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AddClientSecretCommandHandler @Inject()()
                                             (implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: AddClientSecret): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map(isAdminIfInProduction(cmd.instigator, app))(_ => app)
  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: AddClientSecret): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      ClientSecretAdded(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(cmd.email),
        secretValue = cmd.secretValue,
        clientSecret = cmd.clientSecret,
        requestingAdminEmail = getRequester(app, cmd.instigator)
      )
    )
  }

  def process(app: ApplicationData, cmd: AddClientSecret): CommandHandler.Result = {
    Future.successful {
      validate(app, cmd) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}