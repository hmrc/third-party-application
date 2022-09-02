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
  //case class ScheduledJobActor(jobId: String) extends Actor
  //case class UnknownActor() extends Actor

  object Actor {
    implicit val gatekeeperUserActorFormat: OFormat[GatekeeperUserActor] = Json.format[GatekeeperUserActor]
    implicit val collaboratorActorFormat: OFormat[CollaboratorActor] = Json.format[CollaboratorActor]

    implicit val formatActor: OFormat[Actor] = Union.from[Actor]("actorType")
      .and[GatekeeperUserActor](ActorType.GATEKEEPER.toString)
      .and[CollaboratorActor](ActorType.COLLABORATOR.toString)
      .format
  }

  case class Id(value: UUID) extends AnyVal

  object Id {
    implicit val format = Json.valueFormat[Id]

    def random: Id = Id(UUID.randomUUID)
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
    requestingAdminName: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersNotification

  object ResponsibleIndividualChanged {
    implicit val format: OFormat[ResponsibleIndividualChanged] = Json.format[ResponsibleIndividualChanged]
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

  case class ResponsibleIndividualVerificationCompleted(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    code: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent

  object ResponsibleIndividualVerificationCompleted {
    implicit val format: OFormat[ResponsibleIndividualVerificationCompleted] = Json.format[ResponsibleIndividualVerificationCompleted]
  }

  implicit val formatUpdatepplicationEvent: OFormat[UpdateApplicationEvent] = Union.from[UpdateApplicationEvent]("eventType")
    .and[ProductionAppNameChanged](EventType.PROD_APP_NAME_CHANGED.toString)
    .and[ProductionAppPrivacyPolicyLocationChanged](EventType.PROD_APP_PRIVACY_POLICY_LOCATION_CHANGED.toString)
    .and[ProductionLegacyAppPrivacyPolicyLocationChanged](EventType.PROD_LEGACY_APP_PRIVACY_POLICY_LOCATION_CHANGED.toString)
    .and[ProductionAppTermsConditionsLocationChanged](EventType.PROD_APP_TERMS_CONDITIONS_LOCATION_CHANGED.toString)
    .and[ProductionLegacyAppTermsConditionsLocationChanged](EventType.PROD_LEGACY_APP_TERMS_CONDITIONS_LOCATION_CHANGED.toString)
    .and[ResponsibleIndividualSet](EventType.RESPONSIBLE_INDIVIDUAL_SET.toString)
    .and[ResponsibleIndividualChanged](EventType.RESPONSIBLE_INDIVIDUAL_CHANGED.toString)
    .and[ApplicationStateChanged](EventType.APPLICATION_STATE_CHANGED.toString)
    .and[ResponsibleIndividualVerificationStarted](EventType.RESPONSIBLE_INDIVIDUAL_VERIFICATION_STARTED.toString)
    .and[ResponsibleIndividualVerificationCompleted](EventType.RESPONSIBLE_INDIVIDUAL_VERIFICATION_COMPLETED.toString)
    .format
}
