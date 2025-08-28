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

package uk.gov.hmrc.thirdpartyapplication.services.commands.clientsecret

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats._
import cats.data._

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.AddClientSecret
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.CredentialConfig
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class AddClientSecretCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    config: CredentialConfig
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private val clientSecretLimit = config.clientSecretLimit

  private def validate(app: StoredApplication, cmd: AddClientSecret): Validated[Failures, Unit] = {
    Apply[Validated[Failures, *]].map2(
      isAdminIfInProduction(cmd.actor, app),
      appHasLessThanLimitOfSecrets(app, clientSecretLimit)
    ) { case _ => () }
  }

  private def asEvents(app: StoredApplication, cmd: AddClientSecret): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.ClientSecretAddedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        clientSecretId = cmd.id.value.toString,
        clientSecretName = cmd.name
      )
    )
  }

  def process(app: StoredApplication, cmd: AddClientSecret): AppCmdResultT = {
    import uk.gov.hmrc.thirdpartyapplication.models.db.StoredClientSecret

    def asStoredClientSecret(cmd: AddClientSecret): StoredClientSecret =
      StoredClientSecret(
        name = cmd.name,
        createdOn = cmd.timestamp,
        lastAccess = None,
        id = cmd.id,
        hashedSecret = cmd.hashedSecret
      )
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.addClientSecret(app.id, asStoredClientSecret(cmd)))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
