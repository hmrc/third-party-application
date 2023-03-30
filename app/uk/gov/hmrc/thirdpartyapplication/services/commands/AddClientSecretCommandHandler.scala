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

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ClientSecretDetails
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, ClientSecretAddedV2, EventId}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AddClientSecret
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

  private def validate(app: ApplicationData, cmd: AddClientSecret): Validated[CommandHandler.Failures, Unit] = {
    Apply[Validated[CommandHandler.Failures, *]].map2(
      isAdminIfInProduction(cmd.actor, app),
      appHasLessThanLimitOfSecrets(app, clientSecretLimit)
    ) { case _ => () }
  }

  private def asEvents(app: ApplicationData, cmd: AddClientSecret): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ClientSecretAddedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = cmd.actor,
        clientSecretId = cmd.clientSecret.id.value.toString,
        clientSecretName = cmd.clientSecret.name
      )
    )
  }

  def process(app: ApplicationData, cmd: AddClientSecret): ResultT = {
    import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientSecretData

    def asClientSecretData(details: ClientSecretDetails): ClientSecretData =
      ClientSecretData(
        name = details.name,
        createdOn = details.createdOn,
        lastAccess = details.lastAccess,
        id = details.id.value.toString,
        hashedSecret = details.hashedSecret
      )
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.addClientSecret(app.id, asClientSecretData(cmd.clientSecret)))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
