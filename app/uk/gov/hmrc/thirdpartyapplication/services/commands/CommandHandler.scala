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

import scala.concurrent.{ExecutionContext, Future}

import cats.data.{EitherT, NonEmptyChain, NonEmptyList, Validated}
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors}
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress

trait CommandHandler {
  import CommandHandler._

  implicit def ec: ExecutionContext

  val E = EitherTHelper.make[CommandFailures]
}

object CommandHandler {
  type CommandSuccess  = (ApplicationData, NonEmptyList[UpdateApplicationEvent])
  type CommandFailures = NonEmptyChain[String]

  // type Result  = Future[Either[CommandFailures, CommandSuccess]]
  type ResultT = EitherT[Future, CommandFailures, CommandSuccess]

  def cond(cond: => Boolean, left: String): Validated[CommandFailures, Unit] = {
    if (cond) ().validNec[String] else left.invalidNec[Unit]
  }

  def cond[R](cond: => Boolean, left: String, rValue: R): Validated[CommandFailures, R] = {
    if (cond) rValue.validNec[String] else left.invalidNec[R]
  }

  def mustBeDefined[R](value: Option[R], left: String): Validated[CommandFailures, R] = {
    value.fold(left.invalidNec[R])(_.validNec[String])
  }

  def isCollaboratorOnApp(email: LaxEmailAddress, app: ApplicationData): Validated[CommandFailures, Unit] =
    cond(app.collaborators.exists(c => c.emailAddress == email), s"no collaborator found with email: ${email.text}")

  private def isCollaboratorActorAndAdmin(actor: Actor, app: ApplicationData): Boolean =
    actor match {
      case Actors.AppCollaborator(emailAddress) => app.collaborators.exists(c => c.isAdministrator && c.emailAddress == emailAddress)
      case _                                 => false
    }

  private def applicationHasAnAdmin(updated: Set[Collaborator]): Boolean = {
    updated.exists(_.isAdministrator)
  }

  def isAdminOnApp(userId: UserId, app: ApplicationData): Validated[CommandFailures, Collaborator] =
    mustBeDefined(app.collaborators.find(c => c.isAdministrator && c.userId == userId), "User must be an ADMIN")

  def isAdminIfInProduction(actor: Actor, app: ApplicationData): Validated[CommandFailures, Unit] =
    cond(
      (app.environment == Environment.PRODUCTION.toString && isCollaboratorActorAndAdmin(actor, app)) || (app.environment == Environment.SANDBOX.toString),
      "App is in PRODUCTION so User must be an ADMIN"
    )

  def isNotInProcessOfBeingApproved(app: ApplicationData): Validated[CommandFailures, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION || app.state.name == State.TESTING,
      "App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION"
    )

  def isApproved(app: ApplicationData): Validated[CommandFailures, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION,
      "App is not in PRE_PRODUCTION or in PRODUCTION state"
    )

  def clientSecretExists(clientSecretId: String, app: ApplicationData) =
    cond(
      app.tokens.production.clientSecrets.exists(_.id == clientSecretId),
      s"Client Secret Id $clientSecretId not found in Application ${app.id.value}"
    )

  def collaboratorAlreadyOnApp(email: LaxEmailAddress, app: ApplicationData) = {
    cond(
      !app.collaborators.exists(_.emailAddress.equalsIgnoreCase(email)),
      s"Collaborator already linked to Application ${app.id.value}"
    )
  }

  def applicationWillHaveAnAdmin(email: LaxEmailAddress, app: ApplicationData) = {
    cond(
      applicationHasAnAdmin(app.collaborators.filterNot(_.emailAddress equalsIgnoreCase email)),
      s"Collaborator is last remaining admin for Application ${app.id.value}"
    )
  }

  def isPendingResponsibleIndividualVerification(app: ApplicationData) =
    cond(
      app.isPendingResponsibleIndividualVerification,
      "App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state"
    )

  def isInTesting(app: ApplicationData) =
    cond(
      app.isInTesting,
      "App is not in TESTING state"
    )

  def isInPendingGatekeeperApprovalOrResponsibleIndividualVerification(app: ApplicationData) =
    cond(
      app.isInPendingGatekeeperApprovalOrResponsibleIndividualVerification,
      "App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION or PENDING_GATEKEEPER_APPROVAL state"
    )

  def isStandardAccess(app: ApplicationData) =
    cond(app.access.accessType == AccessType.STANDARD, "App must have a STANDARD access type")

  def isStandardNewJourneyApp(app: ApplicationData) =
    cond(
      app.access match {
        case Standard(_, _, _, _, _, Some(_)) => true
        case _                                => false
      },
      "Must be a standard new journey application"
    )

  def getRequester(app: ApplicationData, instigator: UserId): LaxEmailAddress = {
    app.collaborators.find(_.userId == instigator).map(_.emailAddress).getOrElse(throw new RuntimeException(s"no collaborator found with instigator's userid: ${instigator}"))
  }

  def getResponsibleIndividual(app: ApplicationData) =
    app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) => Some(responsibleIndividual)
      case _                                                                                            => None
    }

  def ensureResponsibleIndividualDefined(app: ApplicationData) =
    mustBeDefined(getResponsibleIndividual(app), "The responsible individual has not been set for this application")

  def getRequesterEmail(app: ApplicationData): Option[LaxEmailAddress] =
    app.state.requestedByEmailAddress.map(LaxEmailAddress(_))

  def ensureRequesterEmailDefined(app: ApplicationData) =
    mustBeDefined(getRequesterEmail(app), "The requestedByEmailAddress has not been set for this application")

  def getRequesterName(app: ApplicationData): Option[String] =
    app.state.requestedByName.orElse(getRequesterEmail(app).map(_.text))

  def ensureRequesterNameDefined(app: ApplicationData) =
    mustBeDefined(getRequesterName(app), "The requestedByName has not been set for this application")

  def appHasLessThanLimitOfSecrets(app: ApplicationData, clientSecretLimit: Int): Validated[CommandFailures, Unit] =
    cond(app.tokens.production.clientSecrets.size < clientSecretLimit, "Client secret limit has been exceeded")
}
