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
import uk.gov.hmrc.thirdpartyapplication.domain.models.OldActor

import java.time.{LocalDateTime, ZoneOffset}

case class EventId(value: UUID) extends AnyVal

object EventId {
  def random: EventId = EventId(randomUUID())
}

object EventType extends Enumeration {
  type AccessType = Value
  val TEAM_MEMBER_ADDED                   = Value
  val TEAM_MEMBER_REMOVED                 = Value
  val CLIENT_SECRET_ADDED                 = Value
  val CLIENT_SECRET_REMOVED               = Value
  val REDIRECT_URIS_UPDATED               = Value
  val API_SUBSCRIBED                      = Value
  val API_UNSUBSCRIBED                    = Value
  val PROD_APP_NAME_CHANGED               = Value
  implicit val applicationEventTypeFormat = utils.EnumJson.enumFormat(EventType)
}

sealed trait ApplicationEvent {
  def id: EventId
  def applicationId: String
  def eventDateTime: LocalDateTime
}

case class TeamMemberAddedEvent(id: EventId,
                                applicationId: String,
                                eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                actor: OldActor,
                                teamMemberEmail: String,
                                teamMemberRole: String) extends ApplicationEvent

case class TeamMemberRemovedEvent(id: EventId,
                                  applicationId: String,
                                  eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                  actor: OldActor,
                                  teamMemberEmail: String,
                                  teamMemberRole: String) extends ApplicationEvent

case class ClientSecretAddedEvent(id: EventId,
                                  applicationId: String,
                                  eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                  actor: OldActor,
                                  clientSecretId: String) extends ApplicationEvent

case class ClientSecretRemovedEvent(id: EventId,
                                    applicationId: String,
                                    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                    actor: OldActor,
                                    clientSecretId: String) extends ApplicationEvent


// case class PpnsCallBackUriUpdatedEvent(id: EventId,
//                                        applicationId: String,
//                                        eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
//                                        actor: OldActor,
//                                        boxId: String,
//                                        boxName: String,
//                                        oldCallbackUrl: String,
//                                        newCallbackUrl: String) extends ApplicationEvent

case class RedirectUrisUpdatedEvent(id: EventId,
                                    applicationId: String,
                                    eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                    actor: OldActor,
                                    oldRedirectUris: String,
                                    newRedirectUris: String) extends ApplicationEvent

case class ApiSubscribedEvent(id: EventId,
                              applicationId: String,
                              eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                              actor: OldActor,
                              context: String,
                              version: String) extends ApplicationEvent

case class ApiUnsubscribedEvent(id: EventId,
                                applicationId: String,
                                eventDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
                                actor: OldActor,
                                context: String,
                                version: String) extends ApplicationEvent

object ApplicationEventFormats extends utils.UtcMillisDateTimeFormatters {
  import play.api.libs.json._
  import uk.gov.hmrc.play.json.Union

  implicit val eventIdFormat: Format[EventId]                                     = Json.valueFormat[EventId]
  implicit val oldActorFormat: OFormat[OldActor]                                  = Json.format[OldActor]

  implicit val teamMemberAddedEventFormats: OFormat[TeamMemberAddedEvent]         = Json.format[TeamMemberAddedEvent]
  implicit val teamMemberRemovedEventFormats: OFormat[TeamMemberRemovedEvent]     = Json.format[TeamMemberRemovedEvent]
  implicit val clientSecretAddedEventFormats: OFormat[ClientSecretAddedEvent]     = Json.format[ClientSecretAddedEvent]
  implicit val clientSecretRemovedEventFormats: OFormat[ClientSecretRemovedEvent] = Json.format[ClientSecretRemovedEvent]
  implicit val urisUpdatedEventFormats: OFormat[RedirectUrisUpdatedEvent]         = Json.format[RedirectUrisUpdatedEvent]
  implicit val apiSubscribedEventFormats: OFormat[ApiSubscribedEvent]             = Json.format[ApiSubscribedEvent]
  implicit val apiUnsubscribedEventFormats: OFormat[ApiUnsubscribedEvent]         = Json.format[ApiUnsubscribedEvent]


  implicit val formatApplicationEvent: OFormat[ApplicationEvent] = Union.from[ApplicationEvent]("eventType")
    .and[TeamMemberAddedEvent](EventType.TEAM_MEMBER_ADDED.toString)
    .and[TeamMemberRemovedEvent](EventType.TEAM_MEMBER_REMOVED.toString)
    .and[ClientSecretAddedEvent](EventType.CLIENT_SECRET_ADDED.toString)
    .and[ClientSecretRemovedEvent](EventType.CLIENT_SECRET_REMOVED.toString)
    .and[RedirectUrisUpdatedEvent](EventType.REDIRECT_URIS_UPDATED.toString)
    .and[ApiSubscribedEvent](EventType.API_SUBSCRIBED.toString)
    .and[ApiUnsubscribedEvent](EventType.API_UNSUBSCRIBED.toString)
    .format


}
