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

import uk.gov.hmrc.thirdpartyapplication.domain.models.{AddClientSecret, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.CredentialConfig

@Singleton
class AddClientSecretCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    config: CredentialConfig
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private val clientSecretLimit = config.clientSecretLimit

  private def validate(app: ApplicationData, cmd: AddClientSecret): Validated[CommandFailures, Unit] = {
    Apply[Validated[CommandFailures, *]].map2(
      isAdminIfInProduction(cmd.actor, app),
      appHasLessThanLimitOfSecrets(app, clientSecretLimit)
    ) { case _ => () }
  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: AddClientSecret): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      ClientSecretAddedV2(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        clientSecretId = cmd.clientSecret.id,
        clientSecretName = cmd.clientSecret.name
      )
    )
  }

  def process(app: ApplicationData, cmd: AddClientSecret): ResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.addClientSecret(app.id, cmd.clientSecret))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
