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

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{PrivacyPolicyLocation, PrivacyPolicyLocations}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeProductionApplicationPrivacyPolicyLocation
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent,ApplicationEvents,EventId}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ImportantSubmissionData, Standard}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class ChangeProductionApplicationPrivacyPolicyLocationCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  def processLegacyApp(oldUrl: String, app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): AppCmdResultT = {
    def validate: Validated[Failures, String] = {
      val newUrl     = cmd.newLocation match {
        case PrivacyPolicyLocations.Url(value) => Some(value)
        case _                                 => None
      }
      val isJustAUrl = cond(newUrl.isDefined, "Unexpected new PrivacyPolicyLocation type specified for legacy application: " + cmd.newLocation)

      Apply[Validated[Failures, *]].map4(
        isAdminOnApp(cmd.instigator, app),
        isNotInProcessOfBeingApproved(app),
        isStandardAccess(app),
        isJustAUrl
      ) { case _ => newUrl.get }
    }

    def asEvents(newUrl: String): NonEmptyList[ApplicationEvent] = {
      NonEmptyList.one(
        ApplicationEvents.ProductionLegacyAppPrivacyPolicyLocationChanged(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp.instant,
          actor = Actors.AppCollaborator(getRequester(app, cmd.instigator)),
          oldUrl = oldUrl,
          newUrl = newUrl
        )
      )
    }

    cmd.newLocation match {
      case PrivacyPolicyLocations.Url(value) => value
      case _                                 => false
    }

    for {
      newUrl   <- E.fromEither(validate.toEither)
      savedApp <- E.liftF(applicationRepository.updateLegacyApplicationPrivacyPolicyLocation(app.id, newUrl))
      events    = asEvents(newUrl)
    } yield (savedApp, events)
  }

  def processApp(oldLocation: PrivacyPolicyLocation, app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): AppCmdResultT = {
    def validate: Validated[Failures, ApplicationData] = {
      Apply[Validated[Failures, *]].map3(
        isAdminOnApp(cmd.instigator, app),
        isNotInProcessOfBeingApproved(app),
        isStandardAccess(app)
      ) { case _ => app }
    }

    def asEvents: NonEmptyList[ApplicationEvent] = {
      NonEmptyList.one(
        ApplicationEvents.ProductionAppPrivacyPolicyLocationChanged(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp.instant,
          actor = Actors.AppCollaborator(getRequester(app, cmd.instigator)),
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

  def process(app: ApplicationData, cmd: ChangeProductionApplicationPrivacyPolicyLocation): AppCmdResultT = {
    app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, _, privacyPolicyLocation, _))) => processApp(privacyPolicyLocation, app, cmd)
      case Standard(_, _, maybePrivacyPolicyUrl, _, _, None)                                            => processLegacyApp(maybePrivacyPolicyUrl.getOrElse(""), app, cmd)
      case _                                                                                            => processApp(PrivacyPolicyLocations.InDesktopSoftware, app, cmd) // This will not valdate
    }
  }
}
