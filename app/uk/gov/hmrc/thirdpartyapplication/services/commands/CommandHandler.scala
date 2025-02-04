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

import scala.concurrent.ExecutionContext

import cats.data.{NonEmptyList, Validated}
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors.GatekeeperUser
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{Collaborator, State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ImportantSubmissionData
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{CommandFailure, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.services.BaseCommandHandler
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, _}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

trait CommandHandler {
  implicit def ec: ExecutionContext

  val E = EitherTHelper.make[CommandHandler.Failures]
}

object CommandHandler extends BaseCommandHandler[(StoredApplication, NonEmptyList[ApplicationEvent])] {
  import scala.language.implicitConversions

  import CommandFailures._

  def fromStateHistory(stateHistory: StateHistory, requestingAdminName: String, requestingAdminEmail: LaxEmailAddress) =
    ApplicationEvents.ApplicationStateChanged(
      id = EventId.random,
      applicationId = stateHistory.applicationId,
      eventDateTime = stateHistory.changedAt,
      actor = stateHistory.actor,
      stateHistory.previousState.fold("")(_.toString),
      stateHistory.state.toString,
      requestingAdminName,
      requestingAdminEmail
    )

  implicit def toCommandFailure(in: String): CommandFailure = CommandFailures.GenericFailure(in)

  def isAppActorACollaboratorOnApp(actor: Actors.AppCollaborator, app: StoredApplication): Validated[Failures, Unit] =
    cond(app.collaborators.exists(c => c.emailAddress == actor.email), ActorIsNotACollaboratorOnApp)

  def isCollaboratorOnApp(collaborator: Collaborator, app: StoredApplication): Validated[Failures, Unit] = {
    val matchesId: Collaborator => Boolean    = (appCollaborator) => { appCollaborator.userId == collaborator.userId }
    val matchesEmail: Collaborator => Boolean = (appCollaborator) => { appCollaborator.emailAddress == collaborator.emailAddress }

    app.collaborators.find(c => matchesId(c) || matchesEmail(c)) match {
      case Some(c) if c == collaborator => ().validNel[CommandFailure]
      case Some(_)                      => CollaboratorHasMismatchOnApp.invalidNel[Unit]
      case _                            => CollaboratorDoesNotExistOnApp.invalidNel[Unit]
    }
  }

  private def isCollaboratorActorAndAdmin(actor: Actor, app: StoredApplication): Boolean =
    actor match {
      case Actors.AppCollaborator(emailAddress) => app.collaborators.exists(c => c.isAdministrator && c.emailAddress == emailAddress)
      case _                                    => false
    }

  private def applicationHasAnAdmin(updated: Set[Collaborator]): Boolean = {
    updated.exists(_.isAdministrator)
  }

  private def isGatekeeperUser(actor: Actor): Boolean = actor match {
    case GatekeeperUser(user) => true
    case _: Actor             => false
  }

  def isAdminIfInProductionOrGatekeeperActor(actor: Actor, app: StoredApplication): Validated[Failures, Unit] =
    cond(
      (app.environment.isProduction && isCollaboratorActorAndAdmin(actor, app)) || (app.environment.isSandbox) || isGatekeeperUser(actor),
      CommandFailures.GenericFailure("App is in PRODUCTION so User must be an ADMIN or be a Gatekeeper User")
    )

  def isAdminOnApp(userId: UserId, app: StoredApplication): Validated[Failures, Collaborator] =
    mustBeDefined(app.collaborators.find(c => c.isAdministrator && c.userId == userId), "User must be an ADMIN")

  def isAdminIfInProduction(actor: Actor, app: StoredApplication): Validated[Failures, Unit] =
    cond(
      (app.isProduction && isCollaboratorActorAndAdmin(actor, app)) || (app.isSandbox),
      GenericFailure("App is in PRODUCTION so User must be an ADMIN")
    )

  def isNotInProcessOfBeingApproved(app: StoredApplication): Validated[Failures, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION || app.state.name == State.TESTING,
      GenericFailure("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION")
    )

  def isApproved(app: StoredApplication): Validated[Failures, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION,
      GenericFailure("App is not in PRE_PRODUCTION or in PRODUCTION state")
    )

  def collaboratorAlreadyOnApp(email: LaxEmailAddress, app: StoredApplication) = {
    cond(
      !app.collaborators.exists(_.emailAddress == email),
      CollaboratorAlreadyExistsOnApp
    )
  }

  def applicationWillStillHaveAnAdmin(email: LaxEmailAddress, app: StoredApplication) = {
    cond(
      applicationHasAnAdmin(app.collaborators.filterNot(_.emailAddress == email)),
      CannotRemoveLastAdmin
    )
  }

  def isPendingResponsibleIndividualVerification(app: StoredApplication) =
    cond(
      app.isPendingResponsibleIndividualVerification,
      GenericFailure("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state")
    )

  def isPendingRequesterVerification(app: StoredApplication) =
    cond(
      app.isPendingRequesterVerification,
      GenericFailure("App is not in PENDING_REQUESTER_VERIFICATION state")
    )

  def isInPendingGatekeeperApproval(app: StoredApplication) =
    cond(
      app.isPendingGatekeeperApproval,
      GenericFailure("App is not in PENDING_GATEKEEPER_APPROVAL state")
    )

  def isInTesting(app: StoredApplication) =
    cond(
      app.isInTesting,
      GenericFailure("App is not in TESTING state")
    )

  def isInSandboxEnvironment(app: StoredApplication) =
    cond(
      app.isSandbox,
      GenericFailure("App is not in Sandbox environment")
    )

  def isInProduction(app: StoredApplication) =
    cond(
      app.isInProduction,
      GenericFailure("App is not in PRODUCTION state")
    )

  def isInPendingGatekeeperApprovalOrResponsibleIndividualVerification(app: StoredApplication) =
    cond(
      app.isInPendingGatekeeperApprovalOrResponsibleIndividualVerification,
      GenericFailure("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION or PENDING_GATEKEEPER_APPROVAL state")
    )

  def ensureStandardAccess(app: StoredApplication): Validated[Failures, Access.Standard] = {
    val std = app.access match {
      case std: Access.Standard => Some(std)
      case _                    => None
    }
    mustBeDefined(std, GenericFailure("App must have a STANDARD access type"))
  }

  def ensurePrivilegedOrROPCAccess(app: StoredApplication): Validated[Failures, Access] = {
    val access = app.access match {
      case priv: Access.Privileged => Some(priv)
      case ropc: Access.Ropc       => Some(ropc)
      case _                       => None
    }
    mustBeDefined(access, GenericFailure("App must have a PRIVILEGED or ROPC access type"))
  }

  def isStandardNewJourneyApp(app: StoredApplication) =
    cond(
      app.access match {
        case Access.Standard(_, _, _, _, _, _, Some(_)) => true
        case _                                          => false
      },
      GenericFailure("Must be a standard new journey application")
    )

  def getRequester(app: StoredApplication, instigator: UserId): LaxEmailAddress = {
    app.collaborators.find(_.userId == instigator).map(_.emailAddress).getOrElse(throw new RuntimeException(s"no collaborator found with instigator's userid: ${instigator}"))
  }

  def getResponsibleIndividual(app: StoredApplication) =
    app.access match {
      case Access.Standard(_, _, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) => Some(responsibleIndividual)
      case _                                                                                                      => None
    }

  def ensureResponsibleIndividualDefined(app: StoredApplication) =
    mustBeDefined(getResponsibleIndividual(app), "The responsible individual has not been set for this application")

  def getRequesterEmail(app: StoredApplication): Option[LaxEmailAddress] =
    app.state.requestedByEmailAddress.map(LaxEmailAddress(_))

  def ensureRequesterEmailDefined(app: StoredApplication) =
    mustBeDefined(getRequesterEmail(app), "The requestedByEmailAddress has not been set for this application")

  def getRequesterName(app: StoredApplication): Option[String] =
    app.state.requestedByName.orElse(getRequesterEmail(app).map(_.text))

  def ensureRequesterNameDefined(app: StoredApplication) =
    mustBeDefined(getRequesterName(app), "The requestedByName has not been set for this application")

  def appHasLessThanLimitOfSecrets(app: StoredApplication, clientSecretLimit: Int): Validated[Failures, Unit] =
    cond(app.tokens.production.clientSecrets.size < clientSecretLimit, GenericFailure("Client secret limit has been exceeded"))

  def getVerificationCode(app: StoredApplication): Option[String] =
    app.state.verificationCode

  def ensureVerificationCodeDefined(app: StoredApplication) =
    mustBeDefined(getVerificationCode(app), "The verificationCode has not been set for this application")
}
