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

package uk.gov.hmrc.thirdpartyapplication.services.commands.policy

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.data.{NonEmptyList, Validated}
import cats.syntax.apply.*

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.RemoveSandboxApplicationTermsAndConditionsUrl
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.*
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class RemoveSandboxApplicationTermsAndConditionsUrlCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(using val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler.*

  private def validate(
      app: StoredApplication,
      cmd: RemoveSandboxApplicationTermsAndConditionsUrl
    ): Validated[Failures, String] = {

    (
      isInSandboxEnvironment(app),
      isApproved(app),
      ensureStandardAccess(app),
      isAppActorACollaboratorOnApp(cmd.actor, app)
    )
      .mapN((_, _, std, _) => std.termsAndConditionsUrl)
      .andThen(termsAndConditionsUrl =>
        mustBeDefined(termsAndConditionsUrl, CommandFailures.GenericFailure("Cannot remove a Terms and Conditions URL that is already empty"))
      )
  }

  private def asEvents(app: StoredApplication, cmd: RemoveSandboxApplicationTermsAndConditionsUrl, oldTermsAndConditionsUrl: String): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.SandboxApplicationTermsAndConditionsUrlRemoved(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        oldTermsAndConditionsUrl
      )
    )
  }

  def process(app: StoredApplication, cmd: RemoveSandboxApplicationTermsAndConditionsUrl): AppCmdResultT = {
    for {
      oldTermsAndConditionsUrl <- E.fromEither(validate(app, cmd).toEither)
      savedApp                 <- E.liftF(applicationRepository.updateLegacyTermsAndConditionsUrl(app.id, None))
      events                    = asEvents(app, cmd, oldTermsAndConditionsUrl)
    } yield (savedApp, events)
  }
}
