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

import cats.Apply
import cats.data.Validated.Valid
import cats.data.{NonEmptyChain, NonEmptyList, ValidatedNec}
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ChangeProductionApplicationName, ChangeProductionApplicationPrivacyPolicyLocation, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, DuplicateName, InvalidName}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService.noExclusions

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ChangeProductionApplicationPrivacyPolicyLocationCommandHandler @Inject()()(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map3(
      isAdminOnApp(cmd.instigator, app),
      isNotInProcessOfBeingApproved(app),
      isStandardAccess(app)
    ) { case _ => app }
  }

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      ProductionAppPrivacyPolicyLocationChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(getRequester(app, cmd.instigator)),
        oldLocation = cmd.oldLocation,
        newLocation = cmd.newLocation
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): CommandHandler.Result = {
    val x = validate(app, cmd) map { _ =>
      asEvents(app, cmd)
    }
    // Future[ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]]]
    val v: ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]] = x.leftMap(_ => NonEmptyChain.one("godammit"))
     Future.successful(v)
  }
}
