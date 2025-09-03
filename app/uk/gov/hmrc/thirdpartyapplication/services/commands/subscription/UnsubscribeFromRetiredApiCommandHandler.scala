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

package uk.gov.hmrc.thirdpartyapplication.services.commands.subscription

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.data._

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UnsubscribeFromRetiredApi
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, EventId}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class UnsubscribeFromRetiredApiCommandHandler @Inject() (
    subscriptionRepository: SubscriptionRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def asEvents(app: StoredApplication, cmd: UnsubscribeFromRetiredApi): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApiUnsubscribedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        context = cmd.apiIdentifier.context,
        version = cmd.apiIdentifier.versionNbr
      )
    )
  }

  def process(app: StoredApplication, cmd: UnsubscribeFromRetiredApi): AppCmdResultT = {
    for {
      // This command SHOULD always be created inside TPA and sent from a list of apps subscribed to an API.
      // NO commands should be created for an app, that shouldn't be unsubscribed from a retired API.
      _ <- E.liftF(subscriptionRepository.remove(app.id, cmd.apiIdentifier))
    } yield (app, asEvents(app, cmd))
  }
}
