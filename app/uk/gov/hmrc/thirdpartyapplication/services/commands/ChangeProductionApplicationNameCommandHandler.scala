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

import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.DuplicateName
import uk.gov.hmrc.thirdpartyapplication.models.InvalidName
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationNameValidationResult
import uk.gov.hmrc.thirdpartyapplication.domain.models.ChangeProductionApplicationName
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService.noExclusions

import scala.concurrent.ExecutionContext
import javax.inject.{Inject, Singleton}
import cats.Apply
import cats.data.ValidatedNec
import cats.data.NonEmptyList

@Singleton
class ChangeProductionApplicationNameCommandHandler @Inject()(
  namingService: UpliftNamingService
)(implicit val ec: ExecutionContext) extends CommandHandler {
  
  import CommandHandler._
  
  private def validate(app: ApplicationData, cmd: ChangeProductionApplicationName, nameValidationResult: ApplicationNameValidationResult): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map6(
      isAdminOnApp(cmd.instigator, app),
      isNotInProcessOfBeingApproved(app),
      isStandardAccess(app),
      cond(app.name != cmd.newName, "App already has that name"),
      cond(nameValidationResult != DuplicateName, "New name is a duplicate"),
      cond(nameValidationResult != InvalidName, "New name is invalid")
    ) { case _ => app }
  }

  private def asEvents(app: ApplicationData, cmd: ChangeProductionApplicationName): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      UpdateApplicationEvent.NameChanged(
        applicationId = app.id,
        timestamp = cmd.timestamp,
        instigator = cmd.instigator,
        oldName = app.name,
        newName = cmd.newName
      ),
      UpdateApplicationEvent.NameChangedEmailSent(
        applicationId = app.id,
        timestamp = cmd.timestamp,
        instigator = cmd.instigator,
        oldName = app.name,
        newName = cmd.newName,
        requester = getRequester(app, cmd.instigator),
        recipients = getRecipients(app) ++ getResponsibleIndividual(app)
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeProductionApplicationName): CommandHandler.Result = {
    namingService.validateApplicationName(cmd.newName, noExclusions) map { nameValidationResult =>
      validate(app, cmd, nameValidationResult) map { _ =>
        asEvents(app, cmd)
      }
    }
  }
}