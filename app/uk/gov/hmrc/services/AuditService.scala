/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.services

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import javax.inject.Inject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.models.{ApplicationData, Collaborator, OverrideFlag, Standard}
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.services.AuditAction.{AppNameChanged, AppPrivacyPolicyUrlChanged, AppRedirectUrisChanged, AppTermsAndConditionsUrlChanged}
import uk.gov.hmrc.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AuditService @Inject()(val auditConnector: AuditConnector) {

  def audit(action: AuditAction, data: Map[String, String])(implicit hc: HeaderCarrier): Future[AuditResult] =
    audit(action, data, Map.empty)

  def audit(action: AuditAction, data: Map[String, String],
            tags: Map[String, String])(implicit hc: HeaderCarrier): Future[AuditResult] =
    auditConnector.sendEvent(new DataEvent(
      auditSource = "third-party-application",
      auditType = action.auditType,
      tags = hc.toAuditTags(action.name, "-") ++ userContext(hc) ++ tags,
      detail = hc.toAuditDetails(data.toSeq: _*)
    ))

  private def userContext(hc: HeaderCarrier) =
    userContextFromHeaders(hc.headers.toMap)

  private def userContextFromHeaders(headers: Map[String, String]) = {
    def mapHeader(mapping: (String, String)): Option[(String, String)] =
      headers.get(mapping._1) map (mapping._2 -> URLDecoder.decode(_, StandardCharsets.UTF_8.toString))

    val email = mapHeader(LOGGED_IN_USER_EMAIL_HEADER -> "developerEmail")
    val name = mapHeader(LOGGED_IN_USER_NAME_HEADER -> "developerFullName")

    Seq(email, name).flatten.toMap
  }
}

sealed trait AuditAction {
  val auditType: String
  val name: String
}

object AuditAction {

  case object AppCreated extends AuditAction {
    val name = "application has been created"
    val auditType = "ApplicationCreated"
  }

  case object AppNameChanged extends AuditAction {
    val name = "Application name changed"
    val auditType = "ApplicationNameChanged"
  }

  case object AppRedirectUrisChanged extends AuditAction {
    val name = "Application redirect URIs changed"
    val auditType = "ApplicationRedirectUrisChanged"
  }

  case object AppTermsAndConditionsUrlChanged extends AuditAction {
    val name = "Application terms and conditions url changed"
    val auditType = "ApplicationTermsAndConditionsUrlChanged"
  }

  case object AppPrivacyPolicyUrlChanged extends AuditAction {
    val name = "Application Privacy Policy url Changed"
    val auditType = "ApplicationPrivacyPolicyUrlChanged"
  }

  case object Subscribed extends AuditAction {
    val name = "Application Subscribed to API"
    val auditType = "ApplicationSubscribedToAPI"
  }

  case object Unsubscribed extends AuditAction {
    val name = "Application Unsubscribed From API"
    val auditType = "ApplicationUnsubscribedFromAPI"
  }

  case object ClientSecretAdded extends AuditAction {
    val name = "Application Client Secret Added"
    val auditType = "ApplicationClientSecretAdded"
  }

  case object ClientSecretRemoved extends AuditAction {
    val name = "Application Client Secret Removed"
    val auditType = "ApplicationClientSecretRemoved"
  }

  case object ApplicationUpliftRequested extends AuditAction {
    val name = "application uplift to production has been requested"
    val auditType = "ApplicationUpliftRequested"
  }

  case object ApplicationUpliftRequestDeniedDueToNonUniqueName extends AuditAction {
    val name = "application uplift to production request has been denied, due to non-unique name"
    val auditType = "ApplicationUpliftRequestDeniedDueToNonUniqueName"
  }

  case object ApplicationUpliftVerified extends AuditAction {
    val name = "application uplift to production completed - the verification link sent to the uplift requester has been visited"
    val auditType = "ApplicationUpliftedToProduction"
  }

  case object ApplicationUpliftApproved extends AuditAction {
    val name = "application name approved - as part of the application uplift to production"
    val auditType = "ApplicationNameApprovedByGatekeeper"
  }

  case object ApplicationUpliftRejected extends AuditAction {
    val name = "application name declined - as part of the application uplift production"
    val auditType = "ApplicationNameDeclinedByGatekeeper"
  }

  case object ApplicationVerficationResent extends AuditAction {
    val name = "verification email has been resent"
    val auditType = "VerificationEmailResentByGatekeeper"
  }

  case object CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName extends AuditAction {
    val name = "create privileged application request has been denied, due to non-unique name"
    val auditType = "CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName"
  }

  case object CreateRopcApplicationRequestDeniedDueToNonUniqueName extends AuditAction {
    val name = "create ropc application request has been denied, due to non-unique name"
    val auditType = "CreateRopcApplicationRequestDeniedDueToNonUniqueName"
  }

  case object CollaboratorAdded extends AuditAction {
    val name = "Collaborator added to an application"
    val auditType = "CollaboratorAddedToApplication"

    def details(collaborator: Collaborator) = Map(
      "newCollaboratorEmail" -> collaborator.emailAddress,
      "newCollaboratorType" -> collaborator.role.toString)
  }

  case object CollaboratorRemoved extends AuditAction {
    val name = "Collaborator removed from an application"
    val auditType = "CollaboratorRemovedFromApplication"

    def details(collaborator: Collaborator) = Map(
      "removedCollaboratorEmail" -> collaborator.emailAddress,
      "removedCollaboratorType" -> collaborator.role.toString)
  }

  case object ScopeAdded extends AuditAction {
    val name = "Scope added to an application"
    val auditType = "ScopeAddedToApplication"

    def details(scope: String) = Map("newScope" -> scope)
  }

  case object ScopeRemoved extends AuditAction {
    val name = "Scope removed from an application"
    val auditType = "ScopeRemovedFromApplication"

    def details(scope: String) = Map("removedScope" -> scope)
  }

  case object OverrideAdded extends AuditAction {
    val name = "Override added to an application"
    val auditType = "OverrideAddedToApplication"

    def details(anOverride: OverrideFlag) = Map("newOverride" -> anOverride.overrideType.toString)
  }

  case object OverrideRemoved extends AuditAction {
    val name = "Override removed from an application"
    val auditType = "OverrideRemovedFromApplication"

    def details(anOverride: OverrideFlag) = Map("removedOverride" -> anOverride.overrideType.toString)
  }

  case object ApplicationDeleted extends AuditAction {
    val name = "Application has been deleted"
    val auditType = "ApplicationDeleted"
  }

}

object AuditHelper {

  def applicationId(applicationId: UUID) = Map("applicationId" -> applicationId.toString)

  def calculateAppNameChange(previous: ApplicationData, updated: ApplicationData) =
    if (previous.name != updated.name) Map("newApplicationName" -> updated.name)
    else Map.empty

  def gatekeeperActionDetails(app: ApplicationData) =
    Map("applicationId" -> app.id.toString,
      "applicationName" -> app.name,
      "upliftRequestedByEmail" -> app.state.requestedByEmailAddress.getOrElse("-"),
      "applicationAdmins" -> app.admins.map(_.emailAddress).mkString(", ")
    )

  def calculateAppChanges(previous: ApplicationData, updated: ApplicationData) = {
    val common = Map(
      "applicationId" -> updated.id.toString)

    val genericEvents = Set(calcNameChange(previous, updated))

    val standardEvents = (previous.access, updated.access) match {
      case (p: Standard, u: Standard) => Set(
        calcRedirectUriChange(p, u),
        calcTermsAndConditionsChange(p, u),
        calcPrivacyPolicyChange(p, u))
      case _ => Set.empty
    }

    (standardEvents ++ genericEvents)
      .flatten
      .map(auditEvent => (auditEvent._1, auditEvent._2 ++ common))
  }

  private def when[A](pred: Boolean, ret: => A): Option[A] =
    if (pred) Some(ret) else None

  private def calcRedirectUriChange(a: Standard, b: Standard) = {
    val redirectUris = b.redirectUris.mkString(",")
    when(a.redirectUris != b.redirectUris, AppRedirectUrisChanged -> Map("newRedirectUris" -> redirectUris))
  }

  private def calcNameChange(a: ApplicationData, b: ApplicationData) =
    when(a.name != b.name,
      AppNameChanged -> Map("newApplicationName" -> b.name))

  private def calcTermsAndConditionsChange(a: Standard, b: Standard) =
    when(a.termsAndConditionsUrl != b.termsAndConditionsUrl,
      AppTermsAndConditionsUrlChanged -> Map("newTermsAndConditionsUrl" -> b.termsAndConditionsUrl.getOrElse("")))

  private def calcPrivacyPolicyChange(a: Standard, b: Standard) =
    when(a.privacyPolicyUrl != b.privacyPolicyUrl,
      AppPrivacyPolicyUrlChanged -> Map("newPrivacyPolicyUrl" -> b.privacyPolicyUrl.getOrElse("")))
}