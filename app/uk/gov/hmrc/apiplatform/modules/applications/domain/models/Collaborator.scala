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
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress


sealed trait Collaborator {
  def userId: UserId
  def emailAddress: LaxEmailAddress

  def isAdministrator: Boolean
  final def isDeveloper: Boolean = ! isAdministrator

  final def normalise: Collaborator = Collaborator.normalise(this)

  final def describeRole: String = Collaborator.describeRole(this)
}

object Collaborator {

  private sealed trait Role

  private object Roles {
    case object ADMINISTRATOR extends Role
    case object DEVELOPER     extends Role
  }

  def normalise(me: Collaborator): Collaborator = me match {
    case a: Collaborators.Administrator => a.copy(emailAddress = a.emailAddress.normalise())
    case d: Collaborators.Developer => d.copy(emailAddress = d.emailAddress.normalise())
  }

  def describeRole(me: Collaborator): String = me match {
    case a: Collaborators.Administrator => Roles.ADMINISTRATOR.toString
    case d: Collaborators.Developer => Roles.DEVELOPER.toString
  }

  import play.api.libs.json.Json
  import play.api.libs.json.OFormat
  import uk.gov.hmrc.play.json.Union
  
  implicit val administratorJf = Json.format[Collaborators.Administrator]
  implicit val developersJf    = Json.format[Collaborators.Developer]

  implicit val collaboratorJf: OFormat[Collaborator] = Union.from[Collaborator]("role")
    .and[Collaborators.Administrator](Roles.ADMINISTRATOR.toString)
    .and[Collaborators.Developer](Roles.DEVELOPER.toString)
    .format
}

object Collaborators {

  case class Administrator(userId: UserId, emailAddress: LaxEmailAddress) extends Collaborator {
    val isAdministrator = true
  }

  case class Developer(userId: UserId, emailAddress: LaxEmailAddress)     extends Collaborator {
    val isAdministrator = false
  }
}
