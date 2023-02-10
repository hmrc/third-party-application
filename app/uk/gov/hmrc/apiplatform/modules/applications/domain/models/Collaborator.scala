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

package uk.gov.hmrc.apiplatform.modules.applications.domain.models

import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId


sealed trait Collaborator {
  def userId: UserId
  def emailAddress: String

  def isAdministrator: Boolean
  def isDeveloper: Boolean = ! isAdministrator
}

object Collaborator {

  def apply(emailAddress: String, role: Collaborators.Role, userId: UserId): Collaborator = role match {
    case Collaborators.Roles.ADMINISTRATOR => Collaborators.Administrator(userId, emailAddress)
    case Collaborators.Roles.DEVELOPER     => Collaborators.Developer(userId, emailAddress)
  }

  import play.api.libs.json.Json
  import play.api.libs.json.OFormat
  import uk.gov.hmrc.play.json.Union
  
  implicit val administratorJf = Json.format[Collaborators.Administrator]
  implicit val developersJf    = Json.format[Collaborators.Developer]

  implicit val collaboratorJf: OFormat[Collaborator] = Union.from[Collaborator]("role")
    .and[Collaborators.Administrator](Collaborators.Roles.ADMINISTRATOR.toString)
    .and[Collaborators.Developer](Collaborators.Roles.DEVELOPER.toString)
    .format
}

object Collaborators {
  sealed trait Role

  object Roles {
    case object ADMINISTRATOR extends Role
    case object DEVELOPER     extends Role
  }

  case class Administrator(userId: UserId, emailAddress: String) extends Collaborator {
    val isAdministrator = true
  }

  case class Developer(userId: UserId, emailAddress: String)     extends Collaborator {
    val isAdministrator = false
  }

  object Role {
    import play.api.libs.json._

    // implicit val developerJF      = Json.format[Collaborators.Roles.DEVELOPER.type]
    // implicit val administratorsJF = Json.format[Collaborators.Roles.ADMINISTRATOR.type]

    implicit val collaboratorsRoleJf: Format[Collaborators.Role] = new Format[Collaborators.Role] {

      override def writes(o: Collaborators.Role): JsValue = JsString(o.toString)

      override def reads(json: JsValue): JsResult[Collaborators.Role] = json match {
        case JsString("DEVELOPER")     => JsSuccess(Collaborators.Roles.DEVELOPER)
        case JsString("ADMINISTRATOR") => JsSuccess(Collaborators.Roles.ADMINISTRATOR)
        case JsString(text)            => JsError(s"There is no role for '$text'")
        case _                         => JsError(s"Cannot read role")
      }
    }
  }
}
