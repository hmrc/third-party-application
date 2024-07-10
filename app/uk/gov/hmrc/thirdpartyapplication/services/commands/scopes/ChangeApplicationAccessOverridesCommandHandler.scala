/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services.commands.scopes

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.concurrent.Future.sequence

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, OverrideFlag}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeApplicationAccessOverrides
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{OverrideAdded, OverrideRemoved}
import uk.gov.hmrc.thirdpartyapplication.services.AuditService
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ChangeApplicationAccessOverridesCommandHandler @Inject() (
    val applicationRepository: ApplicationRepository,
    auditService: AuditService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler with ApplicationLogger with ClockNow {

  import CommandHandler._

  private def validate(app: StoredApplication): Validated[Failures, StoredApplication] = {
    Apply[Validated[Failures, *]].map(
      ensureStandardAccess(app)
    ) { case _ => app }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: ChangeApplicationAccessOverrides,
      oldOverrides: Set[OverrideFlag],
      newOverrides: Set[OverrideFlag]
    ): NonEmptyList[ApplicationEvent] = {

    NonEmptyList.of(ApplicationAccessOverridesChanged(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
      oldOverrides,
      newOverrides
    ))

  }

  def process(app: StoredApplication, cmd: ChangeApplicationAccessOverrides)(implicit hc: HeaderCarrier): AppCmdResultT = {

    def updateWithOverrides(applicationData: StoredApplication, newOverrides: Set[OverrideFlag]): StoredApplication =
      applicationData.copy(access = getStandardAccess(applicationData).copy(overrides = newOverrides))

    for {
      validateResult           <- E.fromValidated(validate(app))
      oldOverrides              = getOverrides(app)
      newOverrides              = cmd.overrides
      persistedApplicationData <- E.liftF(applicationRepository.save(updateWithOverrides(app, newOverrides)))
      _                         = sequence(oldOverrides diff newOverrides map OverrideRemoved.details map (auditService.audit(OverrideRemoved, _)))
      _                         = sequence(newOverrides diff oldOverrides map OverrideAdded.details map (auditService.audit(OverrideAdded, _)))
      events                    = asEvents(app, cmd, oldOverrides, newOverrides)
    } yield (app, events)
  }

  private def getOverrides(applicationData: StoredApplication): Set[OverrideFlag] =
    getStandardAccess(applicationData).overrides

  private def getStandardAccess(applicationData: StoredApplication): Access.Standard =
    applicationData.access.asInstanceOf[Access.Standard]
}
