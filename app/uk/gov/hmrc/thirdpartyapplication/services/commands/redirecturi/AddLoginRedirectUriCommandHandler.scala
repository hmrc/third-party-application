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

package uk.gov.hmrc.thirdpartyapplication.services.commands.redirecturi

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats._
import cats.data._
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.LoginRedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class AddLoginRedirectUriCommandHandler @Inject() (applicationRepository: ApplicationRepository)(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._

  private def validate(app: StoredApplication, cmd: AddLoginRedirectUri): Validated[Failures, List[LoginRedirectUri]] = {
    val existingRedirects = app.access match {
      case Access.Standard(redirectUris, _, _, _, _, _, _) => redirectUris
      case _                                               => List.empty
    }

    val hasFourOrFewerURIs = cond((existingRedirects.size < 5), CommandFailures.GenericFailure("Can have at most 5 redirect URIs"))

    Apply[Validated[Failures, *]].map3(
      ensureStandardAccess(app),
      isAdminIfInProduction(cmd.actor, app),
      hasFourOrFewerURIs
    )((_, _, _) => existingRedirects)
  }

  private def asEvents(app: StoredApplication, cmd: AddLoginRedirectUri): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.LoginRedirectUriAdded(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        newRedirectUri = cmd.redirectUriToAdd
      )
    )
  }

  def process(app: StoredApplication, cmd: AddLoginRedirectUri): AppCmdResultT = {
    for {
      existingUris   <- E.fromEither(validate(app, cmd).toEither)
      urisAfterChange = existingUris :+ cmd.redirectUriToAdd
      savedApp       <- E.liftF(applicationRepository.updateLoginRedirectUris(app.id, urisAfterChange))
      events          = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
