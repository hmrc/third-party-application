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

import cats._
import cats.data._
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeSandboxApplicationPrivacyPolicyUrl
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ChangeSandboxApplicationPrivacyPolicyUrlCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(
      app: StoredApplication,
      cmd: ChangeSandboxApplicationPrivacyPolicyUrl
    ): Validated[Failures, Option[String]] = {
    Apply[Validated[Failures, *]].map5(
      isInSandboxEnvironment(app),
      isApproved(app),
      ensureStandardAccess(app),
      isAppActorACollaboratorOnApp(cmd.actor, app),
      cond(cmd.privacyPolicyUrl.isBlank() == false, "Privacy Policy URL cannot be empty")
    ) { case (_, _, std, _, _) => std.privacyPolicyUrl }
  }

  private def asEvents(app: StoredApplication, oldPrivacyPolicyUrl: Option[String], cmd: ChangeSandboxApplicationPrivacyPolicyUrl): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.SandboxApplicationPrivacyPolicyUrlChanged(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        oldPrivacyPolicyUrl,
        privacyPolicyUrl = cmd.privacyPolicyUrl
      )
    )
  }

  def process(app: StoredApplication, cmd: ChangeSandboxApplicationPrivacyPolicyUrl): AppCmdResultT = {
    for {
      oldPrivacyPolicyUrl <- E.fromEither(validate(app, cmd).toEither)
      savedApp            <- E.liftF(applicationRepository.updateLegacyPrivacyPolicyUrl(app.id, Some(cmd.privacyPolicyUrl)))
      events               = asEvents(app, oldPrivacyPolicyUrl, cmd)
    } yield (savedApp, events)
  }
}
