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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import play.api.libs.json._
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.play.json.Union
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role.Role
import uk.gov.hmrc.thirdpartyapplication.models.EventType

sealed trait UpdateApplicationEvent {
  def id: UpdateApplicationEvent.Id
  def applicationId: ApplicationId
  def eventDateTime: LocalDateTime
  def actor: UpdateApplicationEvent.Actor
  def requestingAdminEmail: String
}

trait TriggersNotification {
  self: UpdateApplicationEvent =>
}

object UpdateApplicationEvent {
  sealed trait Actor

  case class GatekeeperUserActor(user: String) extends Actor
  case class CollaboratorActor(email: String) extends Actor
  case class ScheduledJobActor(jobId: String) extends Actor
//  case class UnknownActor() extends Actor

  object Actor {
    implicit val gatekeeperUserActorFormat: OFormat[GatekeeperUserActor] = Json.format[GatekeeperUserActor]
    implicit val collaboratorActorFormat: OFormat[CollaboratorActor] = Json.format[CollaboratorActor]
    implicit val scheduledJobActorFormat: OFormat[ScheduledJobActor] = Json.format[ScheduledJobActor]
//    implicit val unknownActorFormat: OFormat[UnknownActor] = Json.format[UnknownActor]

    implicit val formatActor: OFormat[Actor] = Union.from[Actor]("actorType")
//      .and[UnknownActor](ActorType.UNKNOWN.toString)
      .and[ScheduledJobActor](ActorType.SCHEDULED_JOB.toString)
      .and[GatekeeperUserActor](ActorType.GATEKEEPER.toString)
      .and[CollaboratorActor](ActorType.COLLABORATOR.toString)
      .format
  }

  case class Id(value: UUID) extends AnyVal

  object Id {
    implicit val format = Json.valueFormat[Id]

    def random: Id = Id(UUID.randomUUID)
  }

  case class ClientSecretAdded(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    secretValue: String,
    clientSecret: ClientSecret,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ClientSecretAdded {
    implicit val format: OFormat[ClientSecretAdded] = Json.format[ClientSecretAdded]
  }

  case class ClientSecretAddedObfuscated(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    clientSecretId: String,
    clientSecretName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent

  object ClientSecretAddedObfuscated {
    implicit val format: OFormat[ClientSecretAddedObfuscated] = Json.format[ClientSecretAddedObfuscated]

    def fromClientSecretAdded(evt: ClientSecretAdded)={
      ClientSecretAddedObfuscated(evt.id,
        evt.applicationId,
        evt.eventDateTime,
        evt.actor,
        evt.clientSecret.id,
        evt.clientSecret.name,
        evt.requestingAdminEmail)
    }
  }

  case class ClientSecretRemoved(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    clientSecretId: String,
    clientSecretName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ClientSecretRemoved {
    implicit val format: OFormat[ClientSecretRemoved] = Json.format[ClientSecretRemoved]
  }

  case class ProductionAppNameChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldAppName: String,
    newAppName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ProductionAppNameChanged {
    implicit val format: OFormat[ProductionAppNameChanged] = Json.format[ProductionAppNameChanged]
  }

  case class ProductionAppPrivacyPolicyLocationChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldLocation: PrivacyPolicyLocation,
    newLocation: PrivacyPolicyLocation,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ProductionAppPrivacyPolicyLocationChanged {
    implicit val format: OFormat[ProductionAppPrivacyPolicyLocationChanged] = Json.format[ProductionAppPrivacyPolicyLocationChanged]
  }

  case class ProductionLegacyAppPrivacyPolicyLocationChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldUrl: String,
    newUrl: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ProductionLegacyAppPrivacyPolicyLocationChanged {
    implicit val format: OFormat[ProductionLegacyAppPrivacyPolicyLocationChanged] = Json.format[ProductionLegacyAppPrivacyPolicyLocationChanged]
  }

  case class ProductionAppTermsConditionsLocationChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldLocation: TermsAndConditionsLocation,
    newLocation: TermsAndConditionsLocation,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ProductionAppTermsConditionsLocationChanged {
    implicit val format: OFormat[ProductionAppTermsConditionsLocationChanged] = Json.format[ProductionAppTermsConditionsLocationChanged]
  }

  case class ProductionLegacyAppTermsConditionsLocationChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldUrl: String,
    newUrl: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ProductionLegacyAppTermsConditionsLocationChanged {
    implicit val format: OFormat[ProductionLegacyAppTermsConditionsLocationChanged] = Json.format[ProductionLegacyAppTermsConditionsLocationChanged]
  }

  case class ResponsibleIndividualChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    previousResponsibleIndividualName: String,
    previousResponsibleIndividualEmail: String,
    newResponsibleIndividualName: String,
    newResponsibleIndividualEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    code: String,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ResponsibleIndividualChanged {
    implicit val format: OFormat[ResponsibleIndividualChanged] = Json.format[ResponsibleIndividualChanged]
  }

  case class ResponsibleIndividualChangedToSelf(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    previousResponsibleIndividualName: String,
    previousResponsibleIndividualEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ResponsibleIndividualChangedToSelf {
    implicit val format: OFormat[ResponsibleIndividualChangedToSelf] = Json.format[ResponsibleIndividualChangedToSelf]
  }

  case class ResponsibleIndividualSet(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    responsibleIndividualName: String,
    responsibleIndividualEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    code: String,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent

  object ResponsibleIndividualSet {
    implicit val format: OFormat[ResponsibleIndividualSet] = Json.format[ResponsibleIndividualSet]
  }

  case class ApplicationStateChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldAppState: State,
    newAppState: State,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent

  object ApplicationStateChanged {
    implicit val format: OFormat[ApplicationStateChanged] = Json.format[ApplicationStateChanged]
  }

  case class ResponsibleIndividualVerificationStarted(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    applicationName: String,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    requestingAdminName: String,
    requestingAdminEmail: String,
    responsibleIndividualName: String,
    responsibleIndividualEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    verificationId: ResponsibleIndividualVerificationId
  ) extends UpdateApplicationEvent with TriggersNotification

  object ResponsibleIndividualVerificationStarted {
    implicit val format: OFormat[ResponsibleIndividualVerificationStarted] = Json.format[ResponsibleIndividualVerificationStarted]
  }

  case class ResponsibleIndividualDeclined(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    responsibleIndividualName: String,
    responsibleIndividualEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    code: String,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ResponsibleIndividualDeclined {
    implicit val format: OFormat[ResponsibleIndividualDeclined] = Json.format[ResponsibleIndividualDeclined]
  }

  case class ResponsibleIndividualDeclinedUpdate(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    responsibleIndividualName: String,
    responsibleIndividualEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    code: String,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ResponsibleIndividualDeclinedUpdate {
    implicit val format: OFormat[ResponsibleIndividualDeclinedUpdate] = Json.format[ResponsibleIndividualDeclinedUpdate]
  }

  case class ResponsibleIndividualDidNotVerify(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    responsibleIndividualName: String,
    responsibleIndividualEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    code: String,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ResponsibleIndividualDidNotVerify {
    implicit val format: OFormat[ResponsibleIndividualDidNotVerify] = Json.format[ResponsibleIndividualDidNotVerify]
  }

  case class CollaboratorAdded(id: UpdateApplicationEvent.Id,
                               applicationId: ApplicationId,
                               eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                               actor: Actor,
                               collaboratorId: UserId,
                               collaboratorEmail: String,
                               collaboratorRole: Role,
                               verifiedAdminsToEmail: Set[String],
                               requestingAdminEmail: String) extends UpdateApplicationEvent with TriggersNotification

  object CollaboratorAdded {
    implicit val format: OFormat[CollaboratorAdded] = Json.format[CollaboratorAdded]

    def collaboratorFromEvent(evt: CollaboratorAdded) = Collaborator(evt.collaboratorEmail, evt.collaboratorRole, evt.collaboratorId)
  }

  case class CollaboratorRemoved(id: UpdateApplicationEvent.Id,
                               applicationId: ApplicationId,
                               eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                               actor: Actor,
                               collaboratorId: UserId,
                               collaboratorEmail: String,
                               collaboratorRole: Role,
                               notifyCollaborator: Boolean,
                               verifiedAdminsToEmail: Set[String],
                               requestingAdminEmail: String) extends UpdateApplicationEvent with TriggersNotification

  object CollaboratorRemoved {
    implicit val format: OFormat[CollaboratorRemoved] = Json.format[CollaboratorRemoved]

    def collaboratorFromEvent(evt: CollaboratorRemoved) = Collaborator(evt.collaboratorEmail, evt.collaboratorRole, evt.collaboratorId)
  }
  case class ApplicationApprovalRequestDeclined(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    decliningUserName: String,
    decliningUserEmail: String,
    submissionId: Submission.Id,
    submissionIndex: Int,
    reasons: String,
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent

  object ApplicationApprovalRequestDeclined {
    implicit val format: OFormat[ApplicationApprovalRequestDeclined] = Json.format[ApplicationApprovalRequestDeclined]
  }

  implicit val formatUpdatepplicationEvent: OFormat[UpdateApplicationEvent] = Union.from[UpdateApplicationEvent]("eventType")
    .and[ClientSecretAddedObfuscated](EventType.CLIENT_SECRET_ADDED_V2.toString)
    .and[ClientSecretRemoved](EventType.CLIENT_SECRET_REMOVED_V2.toString)
    .and[ProductionAppNameChanged](EventType.PROD_APP_NAME_CHANGED.toString)
    .and[ProductionAppPrivacyPolicyLocationChanged](EventType.PROD_APP_PRIVACY_POLICY_LOCATION_CHANGED.toString)
    .and[ProductionLegacyAppPrivacyPolicyLocationChanged](EventType.PROD_LEGACY_APP_PRIVACY_POLICY_LOCATION_CHANGED.toString)
    .and[ProductionAppTermsConditionsLocationChanged](EventType.PROD_APP_TERMS_CONDITIONS_LOCATION_CHANGED.toString)
    .and[ProductionLegacyAppTermsConditionsLocationChanged](EventType.PROD_LEGACY_APP_TERMS_CONDITIONS_LOCATION_CHANGED.toString)
    .and[ResponsibleIndividualSet](EventType.RESPONSIBLE_INDIVIDUAL_SET.toString)
    .and[ResponsibleIndividualChanged](EventType.RESPONSIBLE_INDIVIDUAL_CHANGED.toString)
    .and[ResponsibleIndividualChangedToSelf](EventType.RESPONSIBLE_INDIVIDUAL_CHANGED_TO_SELF.toString)
    .and[ApplicationStateChanged](EventType.APPLICATION_STATE_CHANGED.toString)
    .and[ResponsibleIndividualVerificationStarted](EventType.RESPONSIBLE_INDIVIDUAL_VERIFICATION_STARTED.toString)
    .and[ResponsibleIndividualDeclined](EventType.RESPONSIBLE_INDIVIDUAL_DECLINED.toString)
    .and[ResponsibleIndividualDeclinedUpdate](EventType.RESPONSIBLE_INDIVIDUAL_DECLINED_UPDATE.toString)
    .and[ResponsibleIndividualDidNotVerify](EventType.RESPONSIBLE_INDIVIDUAL_DID_NOT_VERIFY.toString)
    .and[ApplicationApprovalRequestDeclined](EventType.APPLICATION_APPROVAL_REQUEST_DECLINED.toString)
    .and[CollaboratorAdded](EventType.COLLABORATOR_ADDED.toString)
    .and[CollaboratorRemoved](EventType.COLLABORATOR_REMOVED.toString)
    .format
}
