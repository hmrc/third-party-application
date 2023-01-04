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

import cats.Apply
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyChain, NonEmptyList, ValidatedNec}
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

  private def buildEventForLegacyApp(oldUrl: String, app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): Either[String,UpdateApplicationEvent] = {
    cmd.newLocation match {
      case PrivacyPolicyLocation.Url(newUrl) =>
        Right(ProductionLegacyAppPrivacyPolicyLocationChanged(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = CollaboratorActor(getRequester(app, cmd.instigator)),
          oldUrl = oldUrl,
          newUrl = newUrl
        ))
      case _ => Left("Unexpected new PrivacyPolicyLocation type specified for legacy application: " + cmd.newLocation)
    }
  }

  private def buildEventForNewApp(oldLocation: PrivacyPolicyLocation, app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): UpdateApplicationEvent =
      ProductionAppPrivacyPolicyLocationChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(getRequester(app, cmd.instigator)),
        oldLocation = oldLocation,
        newLocation = cmd.newLocation
      )

  private def asEvents(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): Either[String,UpdateApplicationEvent] = {
    app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, _, privacyPolicyLocation, _))) =>
        Right(buildEventForNewApp(privacyPolicyLocation, app, cmd))
      case Standard(_, _, maybePrivacyPolicyUrl, _, _, None) =>
        buildEventForLegacyApp(maybePrivacyPolicyUrl.getOrElse(""), app, cmd)
      case _ =>
        Left("Unexpected application access value found: " + app.access)
    }
  }

  def process(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): CommandHandler.Result = {
    Future.successful(validate(app, cmd).fold(errs => Invalid(errs), _ => {
      asEvents(app, cmd).fold(e => Invalid(NonEmptyChain.one(e)), event => Valid(NonEmptyList.one(event)))
    }))
  }
}
