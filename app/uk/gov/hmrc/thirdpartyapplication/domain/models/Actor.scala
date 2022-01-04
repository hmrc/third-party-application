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

import uk.gov.hmrc.thirdpartyapplication.domain.utils.EnumJson

object ActorType extends Enumeration {
  type ActorType = Value
  val COLLABORATOR, GATEKEEPER, SCHEDULED_JOB = Value

  implicit val format = EnumJson.enumFormat(ActorType)
}

case class Actor(id: String, actorType: ActorType.ActorType)

object Actor {
  import play.api.libs.json.{OFormat, Json}
  
  implicit val format: OFormat[Actor] = Json.format[Actor]
}
