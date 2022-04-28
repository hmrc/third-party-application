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

package uk.gov.hmrc.thirdpartyapplication.models

import java.util.UUID
import java.util.UUID.randomUUID
import uk.gov.hmrc.thirdpartyapplication.domain.utils
import uk.gov.hmrc.thirdpartyapplication.domain.models.Actor

import java.time.{LocalDateTime, ZoneOffset}

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
  implicit val applicationEventTypeFormat = utils.EnumJson.enumFormat(EventType)
}

trait ApplicationEvent {
  val id: EventId
  val applicationId: String
  val eventDateTime: LocalDateTime
  val eventType: EventType.Value
  val actor: Actor
}

case class TeamMemberAddedEvent(override val id: EventId,
                                override val applicationId: String,
                                override val actor: Actor,
                                override val eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                teamMemberEmail: String,
                                teamMemberRole: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.TEAM_MEMBER_ADDED
}

case class TeamMemberRemovedEvent(override val id: EventId,
                                  override val applicationId: String,
                                  override val eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                  override val actor: Actor,
                                  teamMemberEmail: String,
                                  teamMemberRole: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.TEAM_MEMBER_REMOVED
}

case class ClientSecretAddedEvent(override val id: EventId,
                                  override val applicationId: String,
                                  override val eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                  override val actor: Actor,
                                  clientSecretId: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.CLIENT_SECRET_ADDED
}

case class ClientSecretRemovedEvent(override val id: EventId,
                                    override val applicationId: String,
                                    override val eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                    override val actor: Actor,
                                    clientSecretId: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.CLIENT_SECRET_REMOVED
}

case class RedirectUrisUpdatedEvent(override val id: EventId,
                                    override val applicationId: String,
                                    override val eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                    override val actor: Actor,
                                    oldRedirectUris: String,
                                    newRedirectUris: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.REDIRECT_URIS_UPDATED
}

case class ApiSubscribedEvent(override val id: EventId,
                              override val applicationId: String,
                              override val eventDateTime:  LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                              override val actor: Actor,
                              context: String,
                              version: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.API_SUBSCRIBED
}

case class ApiUnsubscribedEvent(override val id: EventId,
                                override val applicationId: String,
                                override val eventDateTime:  LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                override val actor: Actor,
                                context: String,
                                version: String) extends ApplicationEvent {
  override val eventType: EventType.Value = EventType.API_UNSUBSCRIBED
}


object ApplicationEventFormats extends utils.UtcMillisDateTimeFormatters{
  import play.api.libs.json._
  import uk.gov.hmrc.play.json.Union

  implicit val eventIdFormat: Format[EventId] = Json.valueFormat[EventId]
  implicit val teamMemberAddedEventFormats: OFormat[TeamMemberAddedEvent] = Json.format[TeamMemberAddedEvent]
  implicit val teamMemberRemovedEventFormats: OFormat[TeamMemberRemovedEvent] = Json.format[TeamMemberRemovedEvent]
  implicit val clientSecretAddedEventFormats: OFormat[ClientSecretAddedEvent] = Json.format[ClientSecretAddedEvent]
  implicit val clientSecretRemovedEventFormats: OFormat[ClientSecretRemovedEvent] = Json.format[ClientSecretRemovedEvent]
  implicit val urisUpdatedEventFormats: OFormat[RedirectUrisUpdatedEvent] = Json.format[RedirectUrisUpdatedEvent]
  implicit val apiSubscribedEventFormats: OFormat[ApiSubscribedEvent] =Json.format[ApiSubscribedEvent]
  implicit val apiUnsubscribedEventFormats: OFormat[ApiUnsubscribedEvent] = Json.format[ApiUnsubscribedEvent]

  implicit val formatApplicationEvent: Format[ApplicationEvent] = Union.from[ApplicationEvent]("eventType")
    .and[TeamMemberAddedEvent](EventType.TEAM_MEMBER_ADDED.toString)
    .and[TeamMemberRemovedEvent](EventType.TEAM_MEMBER_REMOVED.toString)
    .and[ClientSecretAddedEvent](EventType.CLIENT_SECRET_ADDED.toString)
    .and[ClientSecretRemovedEvent](EventType.CLIENT_SECRET_REMOVED.toString)
    .and[RedirectUrisUpdatedEvent](EventType.REDIRECT_URIS_UPDATED.toString)
    .and[ApiSubscribedEvent](EventType.API_SUBSCRIBED.toString)
    .and[ApiUnsubscribedEvent](EventType.API_UNSUBSCRIBED.toString)
    .format
}
