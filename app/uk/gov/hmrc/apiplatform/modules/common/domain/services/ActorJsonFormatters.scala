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

package uk.gov.hmrc.apiplatform.modules.common.domain.services

import play.api.libs.json._
import uk.gov.hmrc.play.json.Union
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors}

trait ActorJsonFormatters {
  private sealed trait ActorType

  private object ActorTypes {
    case object COLLABORATOR  extends ActorType
    case object GATEKEEPER    extends ActorType
    case object SCHEDULED_JOB extends ActorType
    case object UNKNOWN       extends ActorType
  }

  implicit val actorsCollaboratorJF   = Json.format[Actors.Collaborator]
  implicit val actorsGatekeeperUserJF = Json.format[Actors.GatekeeperUser]
  implicit val actorsScheduledJobJF   = Json.format[Actors.ScheduledJob]
  implicit val actorsUnknownJF        = Json.format[Actors.Unknown.type]

  implicit val formatNewStyleActor: OFormat[Actor] = Union.from[Actor]("actorType")
    .and[Actors.Collaborator](ActorTypes.COLLABORATOR.toString)
    .and[Actors.GatekeeperUser](ActorTypes.GATEKEEPER.toString)
    .and[Actors.ScheduledJob](ActorTypes.SCHEDULED_JOB.toString)
    .and[Actors.Unknown.type](ActorTypes.UNKNOWN.toString)
    .format
}

object ActorJsonFormatters extends ActorJsonFormatters
