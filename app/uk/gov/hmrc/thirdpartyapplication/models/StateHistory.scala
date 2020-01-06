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

import org.joda.time.DateTime
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.models.ActorType.ActorType
import uk.gov.hmrc.thirdpartyapplication.models.State.State
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.time.DateTimeUtils

object ActorType extends Enumeration {
  type ActorType = Value
  val COLLABORATOR, GATEKEEPER, SCHEDULED_JOB = Value

  implicit val format = EnumJson.enumFormat(ActorType)
}

case class Actor(id: String, actorType: ActorType)

case class StateHistory(applicationId: UUID,
                        state: State,
                        actor: Actor,
                        previousState: Option[State] = None,
                        notes: Option[String] = None,
                        changedAt: DateTime = DateTimeUtils.now)

object StateHistory {
  implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)

  implicit val format1 = EnumJson.enumFormat(State)
  implicit val format2 = Json.format[Actor]
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val format = Json.format[StateHistory]
}

case class StateHistoryResponse(applicationId: UUID,
                                state: State,
                                actor: Actor,
                                notes: Option[String],
                                changedAt: DateTime)

object StateHistoryResponse {
  def from(sh: StateHistory) = StateHistoryResponse(sh.applicationId, sh.state, sh.actor, sh.notes, sh.changedAt)

  implicit val formatState = EnumJson.enumFormat(State)
  implicit val formatActor = Json.format[Actor]
  implicit val format = Json.format[StateHistoryResponse]
}
