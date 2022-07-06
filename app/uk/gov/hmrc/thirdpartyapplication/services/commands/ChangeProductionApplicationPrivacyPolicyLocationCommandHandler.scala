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
import cats.data.{NonEmptyList, ValidatedNec}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ChangeProductionApplicationPrivacyPolicyLocation, ImportantSubmissionData, PrivacyPolicyLocation, Standard, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

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
    val oldLocation = app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, _, privacyPolicyLocation, _))) => privacyPolicyLocation
      case _ => PrivacyPolicyLocation.NoneProvided
    }
    NonEmptyList.of(
      ProductionAppPrivacyPolicyLocationChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(getRequester(app, cmd.instigator)),
        oldLocation = oldLocation,
        newLocation = cmd.newLocation
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): CommandHandler.Result = {
    Future.successful(validate(app, cmd) map { _ =>
      asEvents(app, cmd)
    })
  }
}
