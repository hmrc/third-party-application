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

import cats.Apply
import cats.data.{NonEmptyList, Validated}
import cats.implicits._

import uk.gov.hmrc.thirdpartyapplication.domain.models.{ClientSecret, RemoveClientSecret}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._

@Singleton
class RemoveClientSecretCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: RemoveClientSecret): Validated[CommandFailures, ApplicationData] = {
    Apply[Validated[CommandFailures, *]].map2(
      isAdminIfInProduction(cmd.actor, app),
      clientSecretExists(cmd.clientSecretId, app)
    ) { case _ => app }
  }


  private def asEvents(app: ApplicationData, cmd: RemoveClientSecret): NonEmptyList[AbstractApplicationEvent] = {
    val clientSecret: Option[ClientSecret] = app.tokens.production.clientSecrets.find(_.id == cmd.clientSecretId)
    NonEmptyList.of(
      ClientSecretRemovedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = cmd.actor,
        clientSecretId = cmd.clientSecretId,
        clientSecretName = clientSecret.map(_.name).getOrElse("")
      )
    )
  }

  def process(app: ApplicationData, cmd: RemoveClientSecret): ResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.deleteClientSecret(app.id, cmd.clientSecretId))
      events    = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
