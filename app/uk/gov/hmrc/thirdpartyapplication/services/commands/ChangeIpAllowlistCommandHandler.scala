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

import cats._
import cats.data._
import cats.data.Validated._
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeIpAllowlist
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.CidrBlock
import org.apache.commons.net.util.SubnetUtils
import uk.gov.hmrc.thirdpartyapplication.domain.models.IpAllowlist

@Singleton
class ChangeIpAllowlistCommandHandler @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  // Try to create a new instance of SubnetUtils with an IP address.
  // If it throws an exception then the IP address isn't in CIDR notation and fails this check.
  private def isValidIpCidrBlock(cidrBlock: CidrBlock): Boolean = {
    try {
      new SubnetUtils(cidrBlock.ipAddress)
      true
    } catch {
      case e: IllegalArgumentException => false
    }
  }

  private def validateAllNewIps(cmd: ChangeIpAllowlist): Boolean = {
    cmd.newIpAllowlist.forall(isValidIpCidrBlock(_))
  }

  private def validate(app: ApplicationData, cmd: ChangeIpAllowlist): Validated[Failures, Unit] = {
    Apply[Validated[Failures, *]].map2(
      isAdminIfInProductionOrGatekeeperActor(cmd.actor, app),
      cond(validateAllNewIps(cmd), CommandFailures.GenericFailure("Not all new allowlist IP addresses are valid CIDR blocks"))
    ) { case _ => () }
  }

  private def buildIpAllowlist(cmd: ChangeIpAllowlist) = {
    IpAllowlist(cmd.required, cmd.newIpAllowlist.map(_.ipAddress).toSet)
  }

  private def asEvents(app: ApplicationData, cmd: ChangeIpAllowlist): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApplicationEvents.IpAllowlistCidrBlockChanged(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = cmd.actor,
        required = cmd.required,
        oldIpAllowlist = cmd.oldIpAllowlist,
        newIpAllowlist = cmd.newIpAllowlist
      )
    )
  }

  def process(app: ApplicationData, cmd: ChangeIpAllowlist): AppCmdResultT = {
    for {
      valid    <- E.fromEither(validate(app, cmd).toEither)
      savedApp <- E.liftF(applicationRepository.updateApplicationIpAllowlist(app.id, buildIpAllowlist(cmd)))
      events    = asEvents(app, cmd)
    } yield (savedApp, events)
  }
}
