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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ImportantSubmissionData, TermsAndConditionsLocation, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeProductionApplicationTermsAndConditionsLocation
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, ApplicationEvents, EventId}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class ChangeProductionApplicationTermsAndConditionsLocationCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  def processLegacyApp(oldUrl: String, app: StoredApplication, cmd: ChangeProductionApplicationTermsAndConditionsLocation): AppCmdResultT = {
    def validate: Validated[Failures, String] = {
      val newUrl       = cmd.newLocation match {
        case TermsAndConditionsLocations.Url(value) => Some(value)
        case _                                      => None
      }
      val ensureIsAUrl = mustBeDefined(newUrl, s"Unexpected new TermsAndConditionsLocation type specified for legacy application: " + cmd.newLocation)

      Apply[Validated[Failures, *]].map4(
        isAdminOnApp(cmd.instigator, app),
        isNotInProcessOfBeingApproved(app),
        isStandardAccess(app),
        ensureIsAUrl
      ) { case (_, _, _, url) => url }
    }

    def asEvents(newUrl: String): NonEmptyList[ApplicationEvent] = {
      NonEmptyList.one(
        ApplicationEvents.ProductionLegacyAppTermsConditionsLocationChanged(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = Actors.AppCollaborator(getRequester(app, cmd.instigator)),
          oldUrl = oldUrl,
          newUrl = newUrl
        )
      )
    }

    for {
      newUrl   <- E.fromEither(validate.toEither)
      savedApp <- E.liftF(applicationRepository.updateLegacyApplicationTermsAndConditionsLocation(app.id, newUrl))
      events    = asEvents(newUrl)
    } yield (savedApp, events)
  }

  def processApp(oldLocation: TermsAndConditionsLocation, app: StoredApplication, cmd: ChangeProductionApplicationTermsAndConditionsLocation): AppCmdResultT = {
    def validate: Validated[Failures, StoredApplication] = {
      Apply[Validated[Failures, *]].map3(
        isAdminOnApp(cmd.instigator, app),
        isNotInProcessOfBeingApproved(app),
        isStandardAccess(app)
      ) { case _ => app }
    }

    def asEvents: NonEmptyList[ApplicationEvent] = {
      NonEmptyList.one(
        ApplicationEvents.ProductionAppTermsConditionsLocationChanged(
          id = EventId.random,
          applicationId = app.id,
          eventDateTime = cmd.timestamp,
          actor = Actors.AppCollaborator(getRequester(app, cmd.instigator)),
          oldLocation = oldLocation,
          newLocation = cmd.newLocation
        )
      )
    }

    for {
      valid    <- E.fromEither(validate.toEither)
      savedApp <- E.liftF(applicationRepository.updateApplicationTermsAndConditionsLocation(app.id, cmd.newLocation))
      events    = asEvents
    } yield (savedApp, events)
  }

  def process(app: StoredApplication, cmd: ChangeProductionApplicationTermsAndConditionsLocation): AppCmdResultT = {
    app.access match {
      case Access.Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, termsAndConditionsLocation, _, _))) => processApp(termsAndConditionsLocation, app, cmd)
      case Access.Standard(_, maybeTermsAndConditionsLocation, _, _, _, None)                                       => processLegacyApp(maybeTermsAndConditionsLocation.getOrElse(""), app, cmd)
      case _                                                                                                        => processApp(TermsAndConditionsLocations.InDesktopSoftware, app, cmd) // This will not valdate
    }
  }
}
