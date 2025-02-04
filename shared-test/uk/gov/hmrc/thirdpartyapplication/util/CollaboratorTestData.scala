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

package uk.gov.hmrc.thirdpartyapplication.util

import scala.collection.mutable

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{CollaboratorFixtures, Collaborators}

trait CollaboratorTestData extends CollaboratorFixtures {

  private val idsByEmail = mutable.Map[String, UserId]()

  lazy val loggedInUserAdminCollaborator = adminTwo
  lazy val otherAdminCollaborator        = adminOne
  lazy val developerCollaborator         = developerOne

  private def idOf(email: Any): UserId = email match {
    case adminOne.emailAddress     => adminOne.userId
    case adminTwo.emailAddress     => adminTwo.userId
    case developerOne.emailAddress => developerOne.userId
    case s: String                 => idsByEmail.getOrElseUpdate(s, UserId.random)
    case LaxEmailAddress(text)     => idsByEmail.getOrElseUpdate(text, UserId.random)
    case _                         => throw new IllegalArgumentException("Only strings and lax email addresses are supported")
  }

  import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax

  implicit class CollaboratorStringSyntax(emailAddress: String) {
    def admin()     = Collaborators.Administrator(idOf(emailAddress), emailAddress.toLaxEmail)
    def developer() = Collaborators.Developer(idOf(emailAddress), emailAddress.toLaxEmail)

    def admin(userId: UserId) = {
      idsByEmail.put(emailAddress, userId)
      Collaborators.Administrator(userId, emailAddress.toLaxEmail)
    }

    def developer(userId: UserId) = {
      idsByEmail.put(emailAddress, userId)
      Collaborators.Developer(userId, emailAddress.toLaxEmail)
    }
  }
}
