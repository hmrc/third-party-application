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

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeRedirectUri
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Standard
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class ChangeRedirectUriCommandHandler @Inject() (applicationRepository: ApplicationRepository)(implicit val ec: ExecutionContext) extends CommandHandler {

  import CommandHandler._
  import cats.syntax.validated._

  private def validate(app: ApplicationData, cmd: ChangeRedirectUri): Validated[Failures, List[String]] = {
    val existingUris = app.access match {
      case Standard(redirectUris, _, _, _, _, _) => redirectUris
      case _                                     => List.empty
    }

    val standardAccess = isStandardAccess(app)
    val uriExists      =
      if (standardAccess.isValid)
        cond(existingUris.contains(cmd.redirectUriToReplace.uri), CommandFailures.GenericFailure(s"RedirectUri ${cmd.redirectUriToReplace.uri} does not exist"))
      else
        ().validNel

    Apply[Validated[Failures, *]].map3(
      standardAccess,
      isAdminIfInProduction(cmd.actor, app),
      uriExists
    )((_, _, _) => existingUris)
  }

  private def asEvents(app: ApplicationData, cmd: ChangeRedirectUri): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      RedirectUriChanged(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = cmd.actor,
        oldRedirectUri = cmd.redirectUriToReplace,
        newRedirectUri = cmd.redirectUri
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeRedirectUri): AppCmdResultT = {
    for {
      existingUris   <- E.fromEither(validate(app, cmd).toEither)
      urisAfterChange = existingUris.map(uriVal => if (uriVal == cmd.redirectUriToReplace.uri) cmd.redirectUri.uri else uriVal)
      savedApp       <- E.liftF(applicationRepository.updateRedirectUris(app.id, urisAfterChange))
      events          = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}