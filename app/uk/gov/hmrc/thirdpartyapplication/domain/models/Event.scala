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
    newLocation: PrivacyPolicyLocation
  ) extends UpdateApplicationEvent

  object ProductionAppPrivacyPolicyLocationChanged {
    implicit val format: OFormat[ProductionAppPrivacyPolicyLocationChanged] = Json.format[ProductionAppPrivacyPolicyLocationChanged]
  }

  implicit val formatUpdatepplicationEvent: OFormat[UpdateApplicationEvent] = Union.from[UpdateApplicationEvent]("eventType")
    .and[ProductionAppNameChanged](EventType.PROD_APP_NAME_CHANGED.toString)
    .and[ProductionAppPrivacyPolicyLocationChanged](EventType.PROD_APP_PRIVACY_POLICY_LOCATION_CHANGED.toString)
    .format
}
