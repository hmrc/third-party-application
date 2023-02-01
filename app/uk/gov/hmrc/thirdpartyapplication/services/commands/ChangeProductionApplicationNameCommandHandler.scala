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
import cats.implicits._
import cats.data._

import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ChangeProductionApplicationName, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ProductionAppNameChanged, GatekeeperUserActor}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, DuplicateName, InvalidName}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService.noExclusions
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class ChangeProductionApplicationNameCommandHandler @Inject() (
  applicationRepository: ApplicationRepository,
  namingService: UpliftNamingService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler2 {

  import CommandHandler2._

  private def validate(app: ApplicationData, cmd: ChangeProductionApplicationName, nameValidationResult: ApplicationNameValidationResult): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map5(
      isAdminOnApp(cmd.instigator, app),
      isNotInProcessOfBeingApproved(app),
      cond(app.name != cmd.newName, "App already has that name"),
      cond(nameValidationResult != DuplicateName, "New name is a duplicate"),
      cond(nameValidationResult != InvalidName, "New name is invalid")
    ) { case _ => app }
  }

  private def asEvents(app: ApplicationData, cmd: ChangeProductionApplicationName): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      ProductionAppNameChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = GatekeeperUserActor(cmd.gatekeeperUser),
        oldAppName = app.name,
        newAppName = cmd.newName,
        requestingAdminEmail = getRequester(app, cmd.instigator)
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeProductionApplicationName): ResultT = {
    for {
      nameValidationResult <- E.liftF(namingService.validateApplicationName(cmd.newName, noExclusions))
      valid    <- E.fromEither(validate(app, cmd, nameValidationResult).toEither)
      savedApp <- E.liftF(applicationRepository.updateApplicationName(app.id, cmd.newName))
      events    = asEvents(savedApp, cmd)
    } yield (savedApp, events)
  }
}
