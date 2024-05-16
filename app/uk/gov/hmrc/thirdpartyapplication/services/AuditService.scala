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

package uk.gov.hmrc.thirdpartyapplication.services

import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, OverrideFlag}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{Mark, Submission}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.{MarkAnswer, QuestionsAndAnswersToMap}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{ApplicationDeleted, _}
import uk.gov.hmrc.thirdpartyapplication.util.HeaderCarrierHelper

// scalastyle:off number.of.types

@Singleton
class AuditService @Inject() (val auditConnector: AuditConnector, val submissionService: SubmissionsService, val clock: Clock)(implicit val ec: ExecutionContext) {

  import cats.instances.future.catsStdInstancesForFuture
  private val E = EitherTHelper.make[String]

  def audit(action: AuditAction, data: Map[String, String])(implicit hc: HeaderCarrier): Future[AuditResult] =
    audit(action, data, Map.empty)

  def audit(action: AuditAction, data: Map[String, String], tags: Map[String, String])(implicit hc: HeaderCarrier): Future[AuditResult] =
    auditConnector.sendEvent(DataEvent(
      auditSource = "third-party-application",
      auditType = action.auditType,
      tags = hc.toAuditTags(action.name, "-") ++ HeaderCarrierHelper.headersToUserContext(hc) ++ tags,
      detail = hc.toAuditDetails(data.toSeq: _*)
    ))

  def auditGatekeeperAction(
      gatekeeperId: String,
      app: StoredApplication,
      action: AuditAction,
      extra: Map[String, String] = Map.empty
    )(implicit hc: HeaderCarrier
    ): Future[AuditResult] = {
    val tags = Map("gatekeeperId" -> gatekeeperId)
    audit(action, AuditHelper.gatekeeperActionDetails(app) ++ extra, tags)
  }

  def applyEvents(app: StoredApplication, events: NonEmptyList[ApplicationEvent])(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    events match {
      case NonEmptyList(e, Nil)  => applyEvent(app, e)
      case NonEmptyList(e, tail) => applyEvent(app, e).flatMap(_ => applyEvents(app, NonEmptyList.fromListUnsafe(tail)))
    }
  }

  // scalastyle:off cyclomatic.complexity
  private def applyEvent(app: StoredApplication, event: ApplicationEvent)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    event match {
      case evt: ApplicationApprovalRequestDeclined             => auditApplicationApprovalRequestDeclined(app, evt)
      case evt: ClientSecretAddedV2                            => auditClientSecretAdded(app, evt)
      case evt: ClientSecretRemovedV2                          => auditClientSecretRemoved(app, evt)
      case evt: CollaboratorAddedV2                            => auditAddCollaborator(app, evt)
      case evt: CollaboratorRemovedV2                          => auditRemoveCollaborator(app, evt)
      case evt: ApplicationDeletedByGatekeeper                 => auditApplicationDeletedByGatekeeper(app, evt)
      case evt: ApiSubscribedV2                                => auditApiSubscribed(app, evt)
      case evt: ApiUnsubscribedV2                              => auditApiUnsubscribed(app, evt)
      case evt: RedirectUrisUpdatedV2                          => auditRedirectUrisUpdated(app, evt)
      case evt: SandboxApplicationNameChanged                  => auditSandboxApplicationNameChangeAction(app, evt)
      case evt: SandboxApplicationPrivacyPolicyUrlChanged      => auditSandboxApplicationPrivacyPolicyUrlChanged(app, evt)
      case evt: SandboxApplicationPrivacyPolicyUrlRemoved      => auditSandboxApplicationPrivacyPolicyUrlRemoved(app, evt)
      case evt: SandboxApplicationTermsAndConditionsUrlChanged => auditSandboxApplicationTermsAndConditionsUrlChanged(app, evt)
      case evt: SandboxApplicationTermsAndConditionsUrlRemoved => auditSandboxApplicationTermsAndConditionsUrlRemoved(app, evt)
      case evt: RequesterEmailVerificationResent               => auditRequesterEmailVerificationResent(app, evt)
      case _                                                   => Future.successful(None)
    }
  }
  // scalastyle:on cyclomatic.complexity

  private def auditSandboxApplicationNameChangeAction(app: StoredApplication, evt: SandboxApplicationNameChanged)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    E.liftF(
      audit(
        AppNameChanged,
        Map("applicationId" -> app.id.value.toString, "oldApplicationName" -> evt.oldName, "newApplicationName" -> evt.newName)
      )
    )
      .toOption
      .value
  }

  private def auditSandboxApplicationTermsAndConditionsUrlChanged(app: StoredApplication, evt: SandboxApplicationTermsAndConditionsUrlChanged)(implicit hc: HeaderCarrier)
      : Future[Option[AuditResult]] = {
    E.liftF(
      audit(
        AppTermsAndConditionsUrlChanged,
        Map("applicationId" -> app.id.value.toString, "newTermsAndConditionsUrl" -> evt.termsAndConditionsUrl)
      )
    )
      .toOption
      .value
  }

  private def auditSandboxApplicationTermsAndConditionsUrlRemoved(app: StoredApplication, evt: SandboxApplicationTermsAndConditionsUrlRemoved)(implicit hc: HeaderCarrier)
      : Future[Option[AuditResult]] = {
    E.liftF(
      audit(
        AppTermsAndConditionsUrlChanged,
        Map("applicationId" -> app.id.value.toString, "newTermsAndConditionsUrl" -> "")
      )
    )
      .toOption
      .value
  }

  private def auditSandboxApplicationPrivacyPolicyUrlChanged(app: StoredApplication, evt: SandboxApplicationPrivacyPolicyUrlChanged)(implicit hc: HeaderCarrier)
      : Future[Option[AuditResult]] = {
    E.liftF(
      audit(
        AppPrivacyPolicyUrlChanged,
        Map("applicationId" -> app.id.value.toString, "newPrivacyPolicyUrl" -> evt.privacyPolicyUrl)
      )
    )
      .toOption
      .value
  }

  private def auditSandboxApplicationPrivacyPolicyUrlRemoved(app: StoredApplication, evt: SandboxApplicationPrivacyPolicyUrlRemoved)(implicit hc: HeaderCarrier)
      : Future[Option[AuditResult]] = {
    E.liftF(
      audit(
        AppPrivacyPolicyUrlChanged,
        Map("applicationId" -> app.id.value.toString, "newPrivacyPolicyUrl" -> "")
      )
    )
      .toOption
      .value
  }

  private def auditApplicationDeletedByGatekeeper(app: StoredApplication, evt: ApplicationDeletedByGatekeeper)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    E.liftF(auditGatekeeperAction(evt.actor.user, app, ApplicationDeleted, Map("requestedByEmailAddress" -> evt.requestingAdminEmail.text)))
      .toOption
      .value
  }

  private def auditApplicationApprovalRequestDeclined(app: StoredApplication, evt: ApplicationApprovalRequestDeclined)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    (
      for {
        submission  <- E.fromOptionF(submissionService.fetchLatest(evt.applicationId), "No submission provided to audit")
        extraDetails = AuditHelper.createExtraDetailsForApplicationApprovalRequestDeclined(app, submission, evt)
        result      <- E.liftF(auditGatekeeperAction(evt.decliningUserName, app, ApplicationApprovalDeclined, extraDetails))
      } yield result
    )
      .toOption
      .value
  }

  private def auditAddCollaborator(app: StoredApplication, evt: CollaboratorAddedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    E.liftF(audit(CollaboratorAddedAudit, AuditHelper.applicationId(app.id) ++ CollaboratorAddedAudit.details(evt.collaborator)))
      .toOption
      .value
  }

  private def auditRemoveCollaborator(app: StoredApplication, evt: CollaboratorRemovedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    E.liftF(audit(CollaboratorRemovedAudit, AuditHelper.applicationId(app.id) ++ CollaboratorRemovedAudit.details(evt.collaborator)))
      .toOption
      .value
  }

  private def auditApiSubscribed(app: StoredApplication, evt: ApiSubscribedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    E.liftF(audit(
      Subscribed,
      Map("applicationId" -> app.id.value.toString, "apiVersion" -> evt.version.value, "apiContext" -> evt.context.value)
    ))
      .toOption
      .value

  private def auditApiUnsubscribed(app: StoredApplication, evt: ApiUnsubscribedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    E.liftF(audit(
      Unsubscribed,
      Map("applicationId" -> app.id.value.toString, "apiVersion" -> evt.version.value, "apiContext" -> evt.context.value)
    ))
      .toOption
      .value

  private def auditClientSecretAdded(app: StoredApplication, evt: ClientSecretAddedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    E.liftF(audit(
      ClientSecretAddedAudit,
      Map("applicationId" -> app.id.value.toString, "newClientSecret" -> evt.clientSecretName, "clientSecretType" -> "PRODUCTION")
    ))
      .toOption
      .value

  private def auditClientSecretRemoved(app: StoredApplication, evt: ClientSecretRemovedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    E.liftF(audit(
      ClientSecretRemovedAudit,
      Map("applicationId" -> app.id.value.toString, "removedClientSecret" -> evt.clientSecretId)
    ))
      .toOption
      .value

  private def auditRedirectUrisUpdated(app: StoredApplication, evt: RedirectUrisUpdatedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    E.liftF(audit(
      AppRedirectUrisChanged,
      Map("applicationId" -> app.id.value.toString, "newRedirectUris" -> evt.newRedirectUris.mkString(","))
    ))
      .toOption
      .value

  private def auditRequesterEmailVerificationResent(app: StoredApplication, evt: RequesterEmailVerificationResent)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    E.liftF(auditGatekeeperAction(
      evt.actor.user,
      app,
      ApplicationVerificationResent
    ))
      .toOption
      .value

}

sealed trait AuditAction {
  val auditType: String
  val name: String
}

object AuditAction {

  case object AppCreated extends AuditAction {
    val name      = "application has been created"
    val auditType = "ApplicationCreated"
  }

  case object AppNameChanged extends AuditAction {
    val name      = "Application name changed"
    val auditType = "ApplicationNameChanged"
  }

  case object AppRedirectUrisChanged extends AuditAction {
    val name      = "Application redirect URIs changed"
    val auditType = "ApplicationRedirectUrisChanged"
  }

  case object AppTermsAndConditionsUrlChanged extends AuditAction {
    val name      = "Application terms and conditions url changed"
    val auditType = "ApplicationTermsAndConditionsUrlChanged"
  }

  case object AppPrivacyPolicyUrlChanged extends AuditAction {
    val name      = "Application Privacy Policy url Changed"
    val auditType = "ApplicationPrivacyPolicyUrlChanged"
  }

  case object Subscribed extends AuditAction {
    val name      = "Application Subscribed to API"
    val auditType = "ApplicationSubscribedToAPI"
  }

  case object Unsubscribed extends AuditAction {
    val name      = "Application Unsubscribed From API"
    val auditType = "ApplicationUnsubscribedFromAPI"
  }

  case object ClientSecretAddedAudit extends AuditAction {
    val name      = "Application Client Secret Added"
    val auditType = "ApplicationClientSecretAdded"
  }

  case object ClientSecretRemovedAudit extends AuditAction {
    val name      = "Application Client Secret Removed"
    val auditType = "ApplicationClientSecretRemoved"
  }

  case object ApplicationUpliftRequested extends AuditAction {
    val name      = "application uplift to production has been requested"
    val auditType = "ApplicationUpliftRequested"
  }

  case object ApplicationUpliftRequestDeniedDueToNonUniqueName extends AuditAction {
    val name      = "application uplift to production request has been denied, due to non-unique name"
    val auditType = "ApplicationUpliftRequestDeniedDueToNonUniqueName"
  }

  case object CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName extends AuditAction {
    val name      = "create privileged application request has been denied, due to non-unique name"
    val auditType = "CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName"
  }

  case object CreateRopcApplicationRequestDeniedDueToNonUniqueName extends AuditAction {
    val name      = "create ropc application request has been denied, due to non-unique name"
    val auditType = "CreateRopcApplicationRequestDeniedDueToNonUniqueName"
  }

  case object ApplicationUpliftVerified extends AuditAction {
    val name      = "application uplift to production completed - the verification link sent to the uplift requester has been visited"
    val auditType = "ApplicationUpliftedToProduction"
  }

  case object ApplicationUpliftApproved extends AuditAction {
    val name      = "application name approved - as part of the application uplift to production"
    val auditType = "ApplicationNameApprovedByGatekeeper"
  }

  case object ApplicationUpliftRejected extends AuditAction {
    val name      = "application name declined - as part of the application uplift production"
    val auditType = "ApplicationNameDeclinedByGatekeeper"
  }

  case object ApplicationVerificationResent extends AuditAction {
    val name      = "verification email has been resent"
    val auditType = "VerificationEmailResentByGatekeeper"
  }

  case object ApplicationUpliftRequestDeniedDueToDenyListedName extends AuditAction {
    val name      = "application uplift to production request has been denied, due to a deny listed name"
    val auditType = "ApplicationUpliftRequestDeniedDueToDenyListedName"
  }

  case object CreatePrivilegedApplicationRequestDeniedDueToDenyListedName extends AuditAction {
    val name      = "create privileged application request has been denied, due to a deny listed name"
    val auditType = "CreatePrivilegedApplicationRequestDeniedDueToDenyListedName"
  }

  case object CreateRopcApplicationRequestDeniedDueToDenyListedName extends AuditAction {
    val name      = "create ropc application request has been denied, due to a deny listed name"
    val auditType = "CreateRopcApplicationRequestDeniedDueToDenyListedName"
  }

  case object CollaboratorAddedAudit extends AuditAction {
    val name      = "Collaborator added to an application"
    val auditType = "CollaboratorAddedToApplication"

    def details(collaborator: Collaborator) = Map(
      "newCollaboratorEmail" -> collaborator.emailAddress.text,
      "newCollaboratorType"  -> collaborator.describeRole
    )
  }

  case object CollaboratorRemovedAudit extends AuditAction {
    val name      = "Collaborator removed from an application"
    val auditType = "CollaboratorRemovedFromApplication"

    def details(collaborator: Collaborator) = Map(
      "removedCollaboratorEmail" -> collaborator.emailAddress.text,
      "removedCollaboratorType"  -> collaborator.describeRole
    )
  }

  case object ScopeAdded extends AuditAction {
    val name      = "Scope added to an application"
    val auditType = "ScopeAddedToApplication"

    def details(scope: String) = Map("newScope" -> scope)
  }

  case object ScopeRemoved extends AuditAction {
    val name      = "Scope removed from an application"
    val auditType = "ScopeRemovedFromApplication"

    def details(scope: String) = Map("removedScope" -> scope)
  }

  case object OverrideAdded extends AuditAction {
    val name      = "Override added to an application"
    val auditType = "OverrideAddedToApplication"

    def details(anOverride: OverrideFlag) = Map("newOverride" -> OverrideFlag.asOverrideType(anOverride).toString)
  }

  case object OverrideRemoved extends AuditAction {
    val name      = "Override removed from an application"
    val auditType = "OverrideRemovedFromApplication"

    def details(anOverride: OverrideFlag) = Map("removedOverride" -> OverrideFlag.asOverrideType(anOverride).toString)
  }

  case object ApplicationDeleted extends AuditAction {
    val name      = "Application has been deleted"
    val auditType = "ApplicationDeleted"
  }

  case object ApplicationApprovalDeclined extends AuditAction {
    val name      = "Application approval has been declined"
    val auditType = "ApplicationApprovalDeclined"
  }

  case object ApplicationApprovalGranted extends AuditAction {
    val name      = "Application approval has been granted"
    val auditType = "ApplicationApprovalGranted"
  }
}

object AuditHelper {
  import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper._

  def applicationId(applicationId: ApplicationId) = Map("applicationId" -> applicationId.value.toString)

  def calculateAppNameChange(previous: StoredApplication, updated: StoredApplication) =
    if (previous.name != updated.name) Map("newApplicationName" -> updated.name)
    else Map.empty

  def gatekeeperActionDetails(app: StoredApplication) =
    Map(
      "applicationId"          -> app.id.value.toString,
      "applicationName"        -> app.name,
      "upliftRequestedByEmail" -> app.state.requestedByEmailAddress.getOrElse("-"),
      "applicationAdmins"      -> app.admins.map(_.emailAddress.text).mkString(", ")
    )

  def calculateAppChanges(previous: StoredApplication, updated: StoredApplication) = {
    val common = Map("applicationId" -> updated.id.value.toString)

    val genericEvents = Set(calcNameChange(previous, updated))

    val standardEvents = (previous.access, updated.access) match {
      case (p: Access.Standard, u: Access.Standard) => Set(
          calcTermsAndConditionsChange(p, u),
          calcPrivacyPolicyChange(p, u)
        )
      case _                                        => Set.empty
    }

    (standardEvents ++ genericEvents)
      .flatten
      .map(auditEvent => (auditEvent._1, auditEvent._2 ++ common))
  }

  private def when[A](pred: Boolean, ret: => A): Option[A] =
    if (pred) Some(ret) else None

  private def calcNameChange(a: StoredApplication, b: StoredApplication) =
    when(a.name != b.name, AppNameChanged -> Map("newApplicationName" -> b.name))

  private def calcTermsAndConditionsChange(a: Access.Standard, b: Access.Standard) =
    when(
      a.termsAndConditionsUrl != b.termsAndConditionsUrl,
      AppTermsAndConditionsUrlChanged -> Map("newTermsAndConditionsUrl" -> b.termsAndConditionsUrl.getOrElse(""))
    )

  private def calcPrivacyPolicyChange(a: Access.Standard, b: Access.Standard) =
    when(a.privacyPolicyUrl != b.privacyPolicyUrl, AppPrivacyPolicyUrlChanged -> Map("newPrivacyPolicyUrl" -> b.privacyPolicyUrl.getOrElse("")))

  def createExtraDetailsForApplicationApprovalRequestDeclined(app: StoredApplication, submission: Submission, evt: ApplicationApprovalRequestDeclined): Map[String, String] = {

    val fmt = DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC)

    val importantSubmissionData    = app.importantSubmissionData.getOrElse(throw new RuntimeException("No importantSubmissionData found in application"))
    val submissionPreviousInstance = submission.instances.tail.head

    val questionsWithAnswers = QuestionsAndAnswersToMap(submission)

    val declinedData                                           = Map("status" -> "declined", "reasons" -> evt.reasons)
    val submittedOn: Instant                                   = submissionPreviousInstance.statusHistory.find(s => s.isSubmitted).map(_.timestamp).get
    val declinedOn: Instant                                    = submissionPreviousInstance.statusHistory.find(s => s.isDeclined).map(_.timestamp).get
    val responsibleIndividualVerificationDate: Option[Instant] = importantSubmissionData.termsOfUseAcceptances.find(t =>
      (t.submissionId == submission.id && t.submissionInstance == submissionPreviousInstance.index)
    ).map(_.dateTime)
    val dates                                                  = Map(
      "submission.started.date"   -> fmt.format(submission.startedOn.asLocalDateTime),
      "submission.submitted.date" -> fmt.format(submittedOn.asLocalDateTime),
      "submission.declined.date"  -> fmt.format(declinedOn.asLocalDateTime)
    ) ++ responsibleIndividualVerificationDate.fold(Map.empty[String, String])(rivd => Map("responsibleIndividual.verification.date" -> fmt.format(rivd)))

    val markedAnswers = MarkAnswer.markSubmission(submission)
    val nbrOfFails    = markedAnswers.filter(_._2 == Mark.Fail).size
    val nbrOfWarnings = markedAnswers.filter(_._2 == Mark.Warn).size
    val counters      = Map(
      "submission.failures" -> nbrOfFails.toString,
      "submission.warnings" -> nbrOfWarnings.toString
    )

    questionsWithAnswers ++ declinedData ++ dates ++ counters
  }
}

// scalastyle:on number.of.types
