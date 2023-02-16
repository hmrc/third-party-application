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
import java.time.{Clock, LocalDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent

import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{Fail, Submission, Warn}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.{MarkAnswer, QuestionsAndAnswersToMap}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.Actor.getActorIdentifier
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{Standard, UpdateApplicationEvent, _}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{ApplicationDeleted, _}
import uk.gov.hmrc.thirdpartyapplication.util.HeaderCarrierHelper

// scalastyle:off number.of.types

@Singleton
class AuditService @Inject() (val auditConnector: AuditConnector, val submissionService: SubmissionsService, val clock: Clock)(implicit val ec: ExecutionContext)
    extends EitherTHelper[String] {

  import cats.instances.future.catsStdInstancesForFuture

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
      app: ApplicationData,
      action: AuditAction,
      extra: Map[String, String] = Map.empty
    )(implicit hc: HeaderCarrier
    ): Future[AuditResult] = {
    val tags = Map("gatekeeperId" -> gatekeeperId)
    audit(action, AuditHelper.gatekeeperActionDetails(app) ++ extra, tags)
  }

  def applyEvents(app: ApplicationData, events: NonEmptyList[UpdateApplicationEvent])(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    events match {
      case NonEmptyList(e, Nil)  => applyEvent(app, e)
      case NonEmptyList(e, tail) => applyEvent(app, e).flatMap(_ => applyEvents(app, NonEmptyList.fromListUnsafe(tail)))
    }
  }

  // scalastyle:off cyclomatic.complexity
  private def applyEvent(app: ApplicationData, event: UpdateApplicationEvent)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    event match {
      case evt: ApplicationApprovalRequestDeclined => auditApplicationApprovalRequestDeclined(app, evt)
      case evt: ClientSecretAddedV2                => auditClientSecretAdded(app, evt)
      case evt: ClientSecretRemoved                => auditClientSecretRemoved(app, evt)
      case evt: CollaboratorAdded                  => auditAddCollaborator(app, evt)
      case evt: CollaboratorRemoved                => auditRemoveCollaborator(app, evt)
      case evt: ApplicationDeletedByGatekeeper     => auditApplicationDeletedByGatekeeper(app, evt)
      case evt: ApiSubscribed                      => auditApiSubscribed(app, evt)
      case evt: ApiUnsubscribed                    => auditApiUnsubscribed(app, evt)
      case evt: RedirectUrisUpdated                => auditRedirectUrisUpdated(app, evt)
      case _                                       => Future.successful(None)
    }
  }
  // scalastyle:on cyclomatic.complexity

  private def auditApplicationDeletedByGatekeeper(app: ApplicationData, evt: ApplicationDeletedByGatekeeper)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    liftF(auditGatekeeperAction(getActorIdentifier(evt.actor), app, ApplicationDeleted, Map("requestedByEmailAddress" -> evt.requestingAdminEmail)))
      .toOption
      .value
  }

  private def auditApplicationApprovalRequestDeclined(app: ApplicationData, evt: ApplicationApprovalRequestDeclined)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    (
      for {
        submission  <- fromOptionF(submissionService.fetchLatest(evt.applicationId), "No submission provided to audit")
        extraDetails = AuditHelper.createExtraDetailsForApplicationApprovalRequestDeclined(app, submission, evt)
        result      <- liftF(auditGatekeeperAction(evt.decliningUserName, app, ApplicationApprovalDeclined, extraDetails))
      } yield result
    )
      .toOption
      .value
  }

  private def auditAddCollaborator(app: ApplicationData, evt: CollaboratorAdded)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    val collaborator = Collaborator(evt.collaboratorEmail, evt.collaboratorRole, evt.collaboratorId)
    liftF(audit(CollaboratorAddedAudit, AuditHelper.applicationId(app.id) ++ CollaboratorAddedAudit.details(collaborator)))
      .toOption
      .value
  }

  private def auditRemoveCollaborator(app: ApplicationData, evt: CollaboratorRemoved)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] = {
    val collaborator = Collaborator(evt.collaboratorEmail, evt.collaboratorRole, evt.collaboratorId)
    liftF(audit(CollaboratorRemovedAudit, AuditHelper.applicationId(app.id) ++ CollaboratorRemovedAudit.details(collaborator)))
      .toOption
      .value
  }

  private def auditApiSubscribed(app: ApplicationData, evt: ApiSubscribed)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    liftF(audit(
      Subscribed,
      Map("applicationId" -> app.id.value.toString, "apiVersion" -> evt.version, "apiContext" -> evt.context)
    ))
      .toOption
      .value

  private def auditApiUnsubscribed(app: ApplicationData, evt: ApiUnsubscribed)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    liftF(audit(
      Unsubscribed,
      Map("applicationId" -> app.id.value.toString, "apiVersion" -> evt.version, "apiContext" -> evt.context)
    ))
      .toOption
      .value

  private def auditClientSecretAdded(app: ApplicationData, evt: ClientSecretAddedV2)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    liftF(audit(
      ClientSecretAddedAudit,
      Map("applicationId" -> app.id.value.toString, "newClientSecret" -> evt.clientSecretName, "clientSecretType" -> "PRODUCTION")
    ))
      .toOption
      .value

  private def auditClientSecretRemoved(app: ApplicationData, evt: ClientSecretRemoved)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    liftF(audit(
      ClientSecretRemovedAudit,
      Map("applicationId" -> app.id.value.toString, "removedClientSecret" -> evt.clientSecretId)
    ))
      .toOption
      .value

  private def auditRedirectUrisUpdated(app: ApplicationData, evt: RedirectUrisUpdated)(implicit hc: HeaderCarrier): Future[Option[AuditResult]] =
    liftF(audit(
      AppRedirectUrisChanged,
      Map("applicationId" -> app.id.value.toString, "newRedirectUris" -> evt.newRedirectUris.mkString(","))
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

  case object ApplicationVerficationResent extends AuditAction {
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
      "newCollaboratorEmail" -> collaborator.emailAddress,
      "newCollaboratorType"  -> collaborator.role.toString
    )
  }

  case object CollaboratorRemovedAudit extends AuditAction {
    val name      = "Collaborator removed from an application"
    val auditType = "CollaboratorRemovedFromApplication"

    def details(collaborator: Collaborator) = Map(
      "removedCollaboratorEmail" -> collaborator.emailAddress,
      "removedCollaboratorType"  -> collaborator.role.toString
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

    def details(anOverride: OverrideFlag) = Map("newOverride" -> anOverride.overrideType.toString)
  }

  case object OverrideRemoved extends AuditAction {
    val name      = "Override removed from an application"
    val auditType = "OverrideRemovedFromApplication"

    def details(anOverride: OverrideFlag) = Map("removedOverride" -> anOverride.overrideType.toString)
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

  def applicationId(applicationId: ApplicationId) = Map("applicationId" -> applicationId.value.toString)

  def calculateAppNameChange(previous: ApplicationData, updated: ApplicationData) =
    if (previous.name != updated.name) Map("newApplicationName" -> updated.name)
    else Map.empty

  def gatekeeperActionDetails(app: ApplicationData) =
    Map(
      "applicationId"          -> app.id.value.toString,
      "applicationName"        -> app.name,
      "upliftRequestedByEmail" -> app.state.requestedByEmailAddress.getOrElse("-"),
      "applicationAdmins"      -> app.admins.map(_.emailAddress).mkString(", ")
    )

  def calculateAppChanges(previous: ApplicationData, updated: ApplicationData) = {
    val common = Map("applicationId" -> updated.id.value.toString)

    val genericEvents = Set(calcNameChange(previous, updated))

    val standardEvents = (previous.access, updated.access) match {
      case (p: Standard, u: Standard) => Set(
          calcTermsAndConditionsChange(p, u),
          calcPrivacyPolicyChange(p, u)
        )
      case _                          => Set.empty
    }

    (standardEvents ++ genericEvents)
      .flatten
      .map(auditEvent => (auditEvent._1, auditEvent._2 ++ common))
  }

  private def when[A](pred: Boolean, ret: => A): Option[A] =
    if (pred) Some(ret) else None

  private def calcNameChange(a: ApplicationData, b: ApplicationData) =
    when(a.name != b.name, AppNameChanged -> Map("newApplicationName" -> b.name))

  private def calcTermsAndConditionsChange(a: Standard, b: Standard) =
    when(
      a.termsAndConditionsUrl != b.termsAndConditionsUrl,
      AppTermsAndConditionsUrlChanged -> Map("newTermsAndConditionsUrl" -> b.termsAndConditionsUrl.getOrElse(""))
    )

  private def calcPrivacyPolicyChange(a: Standard, b: Standard) =
    when(a.privacyPolicyUrl != b.privacyPolicyUrl, AppPrivacyPolicyUrlChanged -> Map("newPrivacyPolicyUrl" -> b.privacyPolicyUrl.getOrElse("")))

  def createExtraDetailsForApplicationApprovalRequestDeclined(app: ApplicationData, submission: Submission, evt: ApplicationApprovalRequestDeclined): Map[String, String] = {

    val fmt = DateTimeFormatter.ISO_DATE_TIME

    val importantSubmissionData    = app.importantSubmissionData.getOrElse(throw new RuntimeException("No importantSubmissionData found in application"))
    val submissionPreviousInstance = submission.instances.tail.head

    val questionsWithAnswers = QuestionsAndAnswersToMap(submission)

    val declinedData                                                 = Map("status" -> "declined", "reasons" -> evt.reasons)
    val submittedOn: LocalDateTime                                   = submissionPreviousInstance.statusHistory.find(s => s.isSubmitted).map(_.timestamp).get
    val declinedOn: LocalDateTime                                    = submissionPreviousInstance.statusHistory.find(s => s.isDeclined).map(_.timestamp).get
    val responsibleIndividualVerificationDate: Option[LocalDateTime] = importantSubmissionData.termsOfUseAcceptances.find(t =>
      (t.submissionId == submission.id && t.submissionInstance == submissionPreviousInstance.index)
    ).map(_.dateTime)
    val dates                                                        = Map(
      "submission.started.date"   -> submission.startedOn.format(fmt),
      "submission.submitted.date" -> submittedOn.format(fmt),
      "submission.declined.date"  -> declinedOn.format(fmt)
    ) ++ responsibleIndividualVerificationDate.fold(Map.empty[String, String])(rivd => Map("responsibleIndividual.verification.date" -> rivd.format(fmt)))

    val markedAnswers = MarkAnswer.markSubmission(submission)
    val nbrOfFails    = markedAnswers.filter(_._2 == Fail).size
    val nbrOfWarnings = markedAnswers.filter(_._2 == Warn).size
    val counters      = Map(
      "submission.failures" -> nbrOfFails.toString,
      "submission.warnings" -> nbrOfWarnings.toString
    )

    questionsWithAnswers ++ declinedData ++ dates ++ counters
  }
}

// scalastyle:on number.of.types
