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
import scala.concurrent.{ExecutionContext}

import cats.Apply
import cats.data.{NonEmptyList, ValidatedNec}

import uk.gov.hmrc.thirdpartyapplication.domain.models.{
  ChangeProductionApplicationPrivacyPolicyLocation,
  ImportantSubmissionData,
  PrivacyPolicyLocation,
  Standard,
  UpdateApplicationEvent
}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models.PrivacyPolicyLocation.Url

@Singleton
class ChangeProductionApplicationPrivacyPolicyLocationCommandHandler @Inject()(
  applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext)
    extends CommandHandler2 {

  import CommandHandler2._
  import UpdateApplicationEvent._

  def processLegacyApp(oldUrl: String, app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): ResultT = {
    def validate: ValidatedNec[String, String] = {
      val newUrl = cmd.newLocation match {
        case Url(value) => Some(value)
        case _ => None
      }
      val isJustAUrl = cond(newUrl.isDefined, "Unexpected new PrivacyPolicyLocation type specified for legacy application: " + cmd.newLocation)

      Apply[ValidatedNec[String, *]].map4(
        isAdminOnApp(cmd.instigator, app),
        isNotInProcessOfBeingApproved(app),
        isStandardAccess(app),
        isJustAUrl
      ) { case _ => newUrl.get }
    }

    def asEvents(newUrl: String): NonEmptyList[UpdateApplicationEvent] = {
      NonEmptyList.one(
        ProductionLegacyAppPrivacyPolicyLocationChanged(
          id = UpdateApplicationEvent.Id.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = CollaboratorActor(getRequester(app, cmd.instigator)),
          oldUrl = oldUrl,
          newUrl = newUrl
        )
      )
    }
    
    cmd.newLocation match {
        case Url(value) => value
        case _ => false
      }

    for {
      newUrl   <- E.fromEither(validate.toEither)
      savedApp <- E.liftF(applicationRepository.updateLegacyApplicationPrivacyPolicyLocation(app.id, newUrl))
      events    = asEvents(newUrl)
    } yield (savedApp, events)
  }

  def processApp(oldLocation: PrivacyPolicyLocation, app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): ResultT = {
    def validate: ValidatedNec[String, ApplicationData] = {
      Apply[ValidatedNec[String, *]].map3(
        isAdminOnApp(cmd.instigator, app),
        isNotInProcessOfBeingApproved(app),
        isStandardAccess(app)
      ) { case _ => app }
    }

    def asEvents: NonEmptyList[UpdateApplicationEvent] = {
      NonEmptyList.one(
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

    for {
      valid    <- E.fromEither(validate.toEither)
      savedApp <- E.liftF(applicationRepository.updateApplicationPrivacyPolicyLocation(app.id, cmd.newLocation))
      events    = asEvents
    } yield (savedApp, events)
  }

  def process(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): ResultT = {
    app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, _, privacyPolicyLocation, _))) => processApp(privacyPolicyLocation, app, cmd)
      case Standard(_, _, maybePrivacyPolicyUrl, _, _, None)                                            => processLegacyApp(maybePrivacyPolicyUrl.getOrElse(""), app, cmd)
      case _                                                                                            => processApp(PrivacyPolicyLocation.InDesktopSoftware, app, cmd)    // This will not valdate
    }
  }
}
