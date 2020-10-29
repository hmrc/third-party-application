/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.models

import java.util.UUID
import java.util.UUID.randomUUID

import org.joda.time.DateTime


case class EventId(value: UUID) extends AnyVal
object EventId {
  def random: EventId = EventId(randomUUID())
}

object EventType extends Enumeration {
  type AccessType = Value
  val TEAM_MEMBER_ADDED = Value
  val TEAM_MEMBER_REMOVED = Value
  val CLIENT_SECRET_ADDED = Value
  val CLIENT_SECRET_REMOVED = Value
  val REDIRECT_URIS_UPDATED = Value
  val API_SUBSCRIBED = Value
  val API_UNSUBSCRIBED = Value
  implicit val applicationEventTypeFormat = EnumJson.enumFormat(EventType)
}

trait ApplicationEvent {
  val id: EventId
  val applicationId: String
  val eventDateTime: DateTime
  val eventType: EventType.Value
  val actor: Actor
}

case class TeamMemberAddedEvent(override val id: EventId,
                                override val applicationId: String,
                                override val actor: Actor,
                                override val eventDateTime: DateTime = DateTime.now(),
                                teamMemberEmail: String,
                                teamMemberRole: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.TEAM_MEMBER_ADDED
}

case class TeamMemberRemovedEvent(override val id: EventId,
                                  override val applicationId: String,
                                  override val eventDateTime: DateTime = DateTime.now(),
                                  override val actor: Actor,
                                  teamMemberEmail: String,
                                  teamMemberRole: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.TEAM_MEMBER_REMOVED
}

case class ClientSecretAddedEvent(override val id: EventId,
                                  override val applicationId: String,
                                  override val eventDateTime: DateTime = DateTime.now(),
                                  override val actor: Actor,
                                  clientSecretId: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.CLIENT_SECRET_ADDED
}

case class ClientSecretRemovedEvent(override val id: EventId,
                                    override val applicationId: String,
                                    override val eventDateTime: DateTime = DateTime.now(),
                                    override val actor: Actor,
                                    clientSecretId: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.CLIENT_SECRET_REMOVED
}

case class RedirectUrisUpdatedEvent(override val id: EventId,
                                    override val applicationId: String,
                                    override val eventDateTime: DateTime = DateTime.now(),
                                    override val actor: Actor,
                                    oldRedirectUris: String,
                                    newRedirectUris: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.REDIRECT_URIS_UPDATED
}

case class ApiSubscribedEvent(override val id: EventId,
                              override val applicationId: String,
                              override val eventDateTime:  DateTime = DateTime.now(),
                              override val actor: Actor,
                              context: String,
                              version: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.API_SUBSCRIBED
}

case class ApiUnsubscribedEvent(override val id: EventId,
                                override val applicationId: String,
                                override val eventDateTime:  DateTime = DateTime.now(),
                                override val actor: Actor,
                                context: String,
                                version: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.API_UNSUBSCRIBED
}
