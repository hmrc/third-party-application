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

package uk.gov.hmrc.apiplatform.modules.applications.domain.services

import play.api.libs.json.{Json, OFormat, _}
import uk.gov.hmrc.play.json.Union

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{Collaborator, Collaborators}

trait CollaboratorJsonFormatters {

  implicit val administratorJf = Json.format[Collaborators.Administrator]
  implicit val developersJf    = Json.format[Collaborators.Developer]

  implicit val collaboratorJf: OFormat[Collaborator] = Union.from[Collaborator]("role")
    .and[Collaborators.Administrator](Collaborators.Roles.ADMINISTRATOR.toString)
    .and[Collaborators.Developer](Collaborators.Roles.DEVELOPER.toString)
    .format

  implicit val developerJF      = Json.format[Collaborators.Roles.DEVELOPER.type]
  implicit val administratorsJF = Json.format[Collaborators.Roles.ADMINISTRATOR.type]

  implicit val collaboratorsRoleJf: Format[Collaborators.Role] = new Format[Collaborators.Role] {

    override def writes(o: Collaborators.Role): JsValue = JsString(o.toString)

    override def reads(json: JsValue): JsResult[Collaborators.Role] = json match {
      case JsString("DEVELOPER")     => JsSuccess(Collaborators.Roles.DEVELOPER)
      case JsString("ADMINISTRATOR") => JsSuccess(Collaborators.Roles.ADMINISTRATOR)
      case _                         => JsError(s"Cannot read role")
    }

  }
}

object CollaboratorJsonFormatters extends CollaboratorJsonFormatters
