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
import uk.gov.hmrc.play.json.Union
import uk.gov.hmrc.thirdpartyapplication.models.EventType

sealed trait UpdateApplicationEvent {
  def id: UpdateApplicationEvent.Id
  def applicationId: ApplicationId
  def eventDateTime: LocalDateTime
  def actor: UpdateApplicationEvent.Actor
  def requestingAdminEmail: String
}

sealed trait TriggersNotification {
  self: UpdateApplicationEvent =>
}

sealed trait TriggersStandardNotification extends TriggersNotification {
  self: UpdateApplicationEvent =>

  def fieldName: String
  def previousValue: String
  def newValue: String
}

object UpdateApplicationEvent {
  sealed trait Actor

  case class GatekeeperUserActor(user: String) extends Actor
  //case class CollaboratorActor(email: String) extends Actor
  //case class ScheduledJobActor(jobId: String) extends Actor
  //case class UnknownActor() extends Actor

  object Actor {
    implicit val gatekeeperUserActorFormat: OFormat[GatekeeperUserActor] = Json.format[GatekeeperUserActor]


    implicit val formatActor: OFormat[Actor] = Union.from[Actor]("actorType")
      .and[GatekeeperUserActor](ActorType.GATEKEEPER.toString)
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

  case class TermsAndConditionsUrlChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldTermsAndConditionsUrl: String,
    newTermsAndConditionsUrl: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersStandardNotification {
    def fieldName = "terms and conditions URL"
    def previousValue = oldTermsAndConditionsUrl
    def newValue = newTermsAndConditionsUrl
  }

  object TermsAndConditionsUrlChanged {
    implicit val format: OFormat[TermsAndConditionsUrlChanged] = Json.format[TermsAndConditionsUrlChanged]
  }

  case class PrivacyPolicyUrlChanged(
    id: UpdateApplicationEvent.Id,
    applicationId: ApplicationId,
    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    actor: Actor,
    oldPrivacyPolicyUrl: String,
    newPrivacyPolicyUrl: String,
    requestingAdminEmail: String
  ) extends UpdateApplicationEvent with TriggersStandardNotification {
    def fieldName = "privacy policy URL"
    def previousValue = oldPrivacyPolicyUrl
    def newValue = newPrivacyPolicyUrl
  }

  object PrivacyPolicyUrlChanged {
    implicit val format: OFormat[PrivacyPolicyUrlChanged] = Json.format[PrivacyPolicyUrlChanged]
  }

  implicit val formatUpdatepplicationEvent: OFormat[UpdateApplicationEvent] = Union.from[UpdateApplicationEvent]("eventType")
    .and[ProductionAppNameChanged](EventType.PROD_APP_NAME_CHANGED.toString)
    .format
}
