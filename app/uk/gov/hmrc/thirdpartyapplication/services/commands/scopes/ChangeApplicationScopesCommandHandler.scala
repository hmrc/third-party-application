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
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, AccessType}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeApplicationScopes
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{ScopeAdded, ScopeRemoved}
import uk.gov.hmrc.thirdpartyapplication.services.AuditService
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ChangeApplicationScopesCommandHandler @Inject() (
    val applicationRepository: ApplicationRepository,
    auditService: AuditService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler with ApplicationLogger with ClockNow {

  import CommandHandler._

  private def validate(app: StoredApplication): Validated[Failures, StoredApplication] = {
    Apply[Validated[Failures, *]].map(
      ensurePrivilegedOrROPCAccess(app)
    ) { case _ => app }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: ChangeApplicationScopes,
      oldScopes: Set[String],
      newScopes: Set[String]
    ): NonEmptyList[ApplicationEvent] = {

    NonEmptyList.of(ApplicationScopesChanged(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
      oldScopes,
      newScopes
    ))

  }

  def process(app: StoredApplication, cmd: ChangeApplicationScopes)(implicit hc: HeaderCarrier): AppCmdResultT = {

    def updateWithScopes(applicationData: StoredApplication, newScopes: Set[String]): StoredApplication = {
      val updatedAccess = privilegedOrRopc[Access](
        applicationData, {
          getPrivilegedAccess(_).copy(scopes = newScopes)
        }, {
          getRopcAccess(_).copy(scopes = newScopes)
        }
      )
      applicationData.withAccess(updatedAccess)
    }

    for {
      validateResult           <- E.fromValidated(validate(app))
      oldScopes                 = getScopes(app)
      newScopes                 = cmd.scopes
      persistedApplicationData <- E.liftF(applicationRepository.save(updateWithScopes(app, newScopes)))
      _                         = sequence(oldScopes diff newScopes map ScopeRemoved.details map (auditService.audit(ScopeRemoved, _)))
      _                         = sequence(newScopes diff oldScopes map ScopeAdded.details map (auditService.audit(ScopeAdded, _)))
      events                    = asEvents(app, cmd, oldScopes, newScopes)
    } yield (app, events)
  }

  private def getScopes(applicationData: StoredApplication): Set[String] =
    privilegedOrRopc[Set[String]](
      applicationData, {
        getPrivilegedAccess(_).scopes
      }, {
        getRopcAccess(_).scopes
      }
    )

  private def getPrivilegedAccess(applicationData: StoredApplication): Access.Privileged =
    applicationData.access.asInstanceOf[Access.Privileged]

  private def getRopcAccess(applicationData: StoredApplication): Access.Ropc =
    applicationData.access.asInstanceOf[Access.Ropc]

  private def privilegedOrRopc[T](applicationData: StoredApplication, privilegedFunction: StoredApplication => T, ropcFunction: StoredApplication => T) =
    (applicationData.access.accessType: @unchecked) match { // There is no need to check for AccessType.STANDARD because of validation done earlier
      case AccessType.PRIVILEGED => privilegedFunction(applicationData)
      case AccessType.ROPC       => ropcFunction(applicationData)
    }
}
