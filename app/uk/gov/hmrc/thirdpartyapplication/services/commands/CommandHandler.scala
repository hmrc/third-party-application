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

import java.time.{Instant, LocalDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.{EitherT, NonEmptyChain, NonEmptyList, Validated}
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

trait CommandHandler {
  implicit def ec: ExecutionContext

  val E = EitherTHelper.make[CommandHandler.Failures]
}

object CommandHandler {
  type Success  = (ApplicationData, NonEmptyList[ApplicationEvent])
  type Failures = NonEmptyChain[CommandFailure]

  import CommandFailures._

  type ResultT = EitherT[Future, Failures, Success]

  implicit class InstantSyntax(value: LocalDateTime) {
    def instant: Instant = value.toInstant(ZoneOffset.UTC)
  }

  def fromStateHistory(stateHistory: StateHistory, requestingAdminName: String, requestingAdminEmail: LaxEmailAddress) =
    ApplicationStateChanged(
      id = EventId.random,
      applicationId = stateHistory.applicationId,
      eventDateTime = stateHistory.changedAt.instant,
      actor = stateHistory.actor,
      stateHistory.previousState.fold("")(_.toString),
      stateHistory.state.toString,
      requestingAdminName,
      requestingAdminEmail
    )

  def cond(cond: => Boolean, left: CommandFailure): Validated[Failures, Unit] = {
    if (cond) ().validNec[CommandFailure] else left.invalidNec[Unit]
  }

  def cond(cond: => Boolean, left: String): Validated[Failures, Unit] = {
    if (cond) ().validNec[CommandFailure] else GenericFailure(left).invalidNec[Unit]
  }

  def cond[R](cond: => Boolean, left: CommandFailure, rValue: R): Validated[Failures, R] = {
    if (cond) rValue.validNec[CommandFailure] else left.invalidNec[R]
  }

  def mustBeDefined[R](value: Option[R], left: CommandFailure): Validated[Failures, R] = {
    value.fold(left.invalidNec[R])(_.validNec[CommandFailure])
  }

  def mustBeDefined[R](value: Option[R], left: String): Validated[Failures, R] = {
    value.fold[Validated[Failures, R]](GenericFailure(left).invalidNec[R])(_.validNec[CommandFailure])
  }

  def isAppActorACollaboratorOnApp(actor: Actors.AppCollaborator, app: ApplicationData): Validated[Failures, Unit] =
    cond(app.collaborators.exists(c => c.emailAddress == actor.email), ActorIsNotACollaboratorOnApp)

  def isCollaboratorOnApp(collaborator: Collaborator, app: ApplicationData): Validated[Failures, Unit] = {
    val matchesId: Collaborator => Boolean    = (appCollaborator) => { appCollaborator.userId == collaborator.userId }
    val matchesEmail: Collaborator => Boolean = (appCollaborator) => { appCollaborator.emailAddress equalsIgnoreCase collaborator.emailAddress }

    app.collaborators.find(c => matchesId(c) || matchesEmail(c)) match {
      case Some(c) if (c == collaborator) => ().validNec[CommandFailure]
      case Some(_)                        => CollaboratorHasMismatchOnApp.invalidNec[Unit]
      case _                              => CollaboratorDoesNotExistOnApp.invalidNec[Unit]
    }
  }

  private def isCollaboratorActorAndAdmin(actor: Actor, app: ApplicationData): Boolean =
    actor match {
      case Actors.AppCollaborator(emailAddress) => app.collaborators.exists(c => c.isAdministrator && c.emailAddress == emailAddress)
      case _                                    => false
    }

  private def applicationHasAnAdmin(updated: Set[Collaborator]): Boolean = {
    updated.exists(_.isAdministrator)
  }

  def isAdminOnApp(userId: UserId, app: ApplicationData): Validated[Failures, Collaborator] =
    mustBeDefined(app.collaborators.find(c => c.isAdministrator && c.userId == userId), "User must be an ADMIN")

  def isAdminIfInProduction(actor: Actor, app: ApplicationData): Validated[Failures, Unit] =
    cond(
      (app.environment == Environment.PRODUCTION.toString && isCollaboratorActorAndAdmin(actor, app)) || (app.environment == Environment.SANDBOX.toString),
      GenericFailure("App is in PRODUCTION so User must be an ADMIN")
    )

  def isNotInProcessOfBeingApproved(app: ApplicationData): Validated[Failures, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION || app.state.name == State.TESTING,
      GenericFailure("App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION")
    )

  def isApproved(app: ApplicationData): Validated[Failures, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION,
      GenericFailure("App is not in PRE_PRODUCTION or in PRODUCTION state")
    )

  def clientSecretExists(clientSecretId: String, app: ApplicationData) =
    cond(
      app.tokens.production.clientSecrets.exists(_.id == clientSecretId),
      GenericFailure(s"Client Secret Id $clientSecretId not found in Application ${app.id.value}")
    )

  def collaboratorAlreadyOnApp(email: LaxEmailAddress, app: ApplicationData) = {
    cond(
      !app.collaborators.exists(_.emailAddress.equalsIgnoreCase(email)),
      CollaboratorAlreadyExistsOnApp
    )
  }

  def applicationWillStillHaveAnAdmin(email: LaxEmailAddress, app: ApplicationData) = {
    cond(
      applicationHasAnAdmin(app.collaborators.filterNot(_.emailAddress equalsIgnoreCase email)),
      CannotRemoveLastAdmin
    )
  }

  def isPendingResponsibleIndividualVerification(app: ApplicationData) =
    cond(
      app.isPendingResponsibleIndividualVerification,
      GenericFailure("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state")
    )

  def isInTesting(app: ApplicationData) =
    cond(
      app.isInTesting,
      GenericFailure("App is not in TESTING state")
    )

  def isInProduction(app: ApplicationData) =
    cond(
      app.isInProduction,
      GenericFailure("App is not in PRODUCTION state")
    )

  def isInPendingGatekeeperApprovalOrResponsibleIndividualVerification(app: ApplicationData) =
    cond(
      app.isInPendingGatekeeperApprovalOrResponsibleIndividualVerification,
      GenericFailure("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION or PENDING_GATEKEEPER_APPROVAL state")
    )

  def isStandardAccess(app: ApplicationData) =
    cond(app.access.accessType == AccessType.STANDARD, GenericFailure("App must have a STANDARD access type"))

  def isStandardNewJourneyApp(app: ApplicationData) =
    cond(
      app.access match {
        case Standard(_, _, _, _, _, Some(_)) => true
        case _                                => false
      },
      GenericFailure("Must be a standard new journey application")
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

  def appHasLessThanLimitOfSecrets(app: ApplicationData, clientSecretLimit: Int): Validated[Failures, Unit] =
    cond(app.tokens.production.clientSecrets.size < clientSecretLimit, GenericFailure("Client secret limit has been exceeded"))
}
