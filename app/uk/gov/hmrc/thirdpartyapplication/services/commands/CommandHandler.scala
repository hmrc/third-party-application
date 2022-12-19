/*
 * Copyright 2022 HM Revenue & Customs
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

import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import cats.implicits._
import cats.data.NonEmptyList
import cats.data.ValidatedNec
import uk.gov.hmrc.thirdpartyapplication.domain.models.{AccessType, Collaborator, Environment, ImportantSubmissionData, Role, Standard, State, UpdateApplicationEvent, UserId}
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{Actor, CollaboratorActor}

abstract class CommandHandler {
  implicit def ec: ExecutionContext
}

object CommandHandler {
  type Result = Future[ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]]]

  def cond(cond: => Boolean, left: String): ValidatedNec[String, Unit] = {
    if (cond) ().validNec[String] else left.invalidNec[Unit]
  }

  def isCollaboratorOnApp(email: String, app: ApplicationData): ValidatedNec[String, Unit] =
    cond(app.collaborators.exists(c =>  c.emailAddress == email), s"no collaborator found with email: $email")

  private def isAdmin(userId: UserId, app: ApplicationData): Boolean =
    app.collaborators.exists(c => c.role == Role.ADMINISTRATOR && c.userId == userId)

  private def isCollaboratorActorAndAdmin(actor: Actor, app: ApplicationData): Boolean =
    actor match {
      case CollaboratorActor(emailAddress) => app.collaborators.exists(c => c.role == Role.ADMINISTRATOR && c.emailAddress == emailAddress)
      case _                               => false
    }

  private def applicationHasAnAdmin(updated: Set[Collaborator]): Boolean = {
    updated.exists(_.role == Role.ADMINISTRATOR)
  }

  def isAdminOnApp(userId: UserId, app: ApplicationData): ValidatedNec[String, Unit] =
    cond(isAdmin(userId, app), "User must be an ADMIN")

  def isAdminIfInProduction(actor: Actor, app: ApplicationData): ValidatedNec[String, Unit] =
    cond(
      (app.environment == Environment.PRODUCTION.toString && isCollaboratorActorAndAdmin(actor, app)) || (app.environment == Environment.SANDBOX.toString),
      "App is in PRODUCTION so User must be an ADMIN"
    )

  def isNotInProcessOfBeingApproved(app: ApplicationData): ValidatedNec[String, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION || app.state.name == State.TESTING,
      "App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION"
    )

  def isApproved(app: ApplicationData): ValidatedNec[String, Unit] =
    cond(
      app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION,
      "App is not in PRE_PRODUCTION or in PRODUCTION state"
    )

  def clientSecretExists(clientSecretId: String, app: ApplicationData) =
    cond(
      app.tokens.production.clientSecrets.exists(_.id == clientSecretId),
      s"Client Secret Id $clientSecretId not found in Application ${app.id.value}"
    )

  def collaboratorAlreadyOnApp(email: String, app: ApplicationData)  = {
      cond(
      !app.collaborators.exists(_.emailAddress.toLowerCase == email.toLowerCase),
      s"Collaborator already linked to Application ${app.id.value}"
    )
  }

  def applicationWillHaveAnAdmin(email: String, app: ApplicationData) = {
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
    cond(app.access match {
      case Standard(_, _, _, _, _, Some(_)) => true
      case _ => false
    }, "Must be a standard new journey application")

  def getRequester(app: ApplicationData, instigator: UserId) = {
    app.collaborators.find(_.userId == instigator).map(_.emailAddress).getOrElse(throw new RuntimeException(s"no collaborator found with instigator's userid: ${instigator}"))
  }

  def getResponsibleIndividual(app: ApplicationData) =
    app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) => Some(responsibleIndividual)
      case _ => None
  }

  def isResponsibleIndividualDefined(app: ApplicationData) =
    cond(getResponsibleIndividual(app).isDefined, "The responsible individual has not been set for this application")

  def getRequesterEmail(app: ApplicationData) =
    app.state.requestedByEmailAddress

  def isRequesterEmailDefined(app: ApplicationData) =
    cond(getRequesterEmail(app).isDefined, "The requestedByEmailAddress has not been set for this application")

  def getRequesterName(app: ApplicationData) =
    app.state.requestedByName.orElse(getRequesterEmail(app))

  def isRequesterNameDefined(app: ApplicationData) =
    cond(getRequesterName(app).isDefined, "The requestedByName has not been set for this application")
}
