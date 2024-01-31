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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeProductionApplicationName
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, DuplicateName, InvalidName}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService.noExclusions

@Singleton
class ChangeProductionApplicationNameCommandHandler @Inject() (
    applicationRepository: ApplicationRepository,
    namingService: UpliftNamingService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(
      app: StoredApplication,
      cmd: ChangeProductionApplicationName,
      nameValidationResult: ApplicationNameValidationResult
    ): Validated[Failures, StoredApplication] = {
    Apply[Validated[Failures, *]].map5(
      isAdminOnApp(cmd.instigator, app),
      isNotInProcessOfBeingApproved(app),
      cond(app.name != cmd.newName, "App already has that name"),
      cond(nameValidationResult != DuplicateName, "New name is a duplicate"),
      cond(nameValidationResult != InvalidName, "New name is invalid")
    ) { case _ => app }
  }

  private def asEvents(app: StoredApplication, cmd: ChangeProductionApplicationName): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.ProductionAppNameChangedEvent(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
        oldAppName = app.name,
        newAppName = cmd.newName,
        requestingAdminEmail = getRequester(app, cmd.instigator)
      )
    )
  }

  def process(app: StoredApplication, cmd: ChangeProductionApplicationName): AppCmdResultT = {
    for {
      nameValidationResult <- E.liftF(namingService.validateApplicationName(cmd.newName, noExclusions))
      valid                <- E.fromEither(validate(app, cmd, nameValidationResult).toEither)
      savedApp             <- E.liftF(applicationRepository.updateApplicationName(app.id, cmd.newName))
      events                = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
