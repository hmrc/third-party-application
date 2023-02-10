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
}
