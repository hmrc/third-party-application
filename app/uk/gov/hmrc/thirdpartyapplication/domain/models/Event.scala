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
  //case class CollaboratorActor(email: String) extends Actor
  //case class ScheduledJobActor(jobId: String) extends Actor
  //case class UnknownActor() extends Actor

  object Actor {
    implicit val gatekeeperUserActorFormat: OFormat[GatekeeperUserActor] = Json.format[GatekeeperUserActor]


    implicit val formatActor: OFormat[Actor] = Union.from[Actor]("actorType")
      .and[GatekeeperUserActor](ActorType.GATEKEEPER.toString)
      .format
  }

  case class NameChangedEmailSent(applicationId: ApplicationId, timestamp: LocalDateTime, instigator: UserId, oldName: String, newName: String, requester: String)
      extends UpdateApplicationNotificationEvent

}
