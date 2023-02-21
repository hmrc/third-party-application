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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborators
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId

trait CollaboratorTestData {

  private val idsByEmail = mutable.Map[String, UserId]()

  def idOf(email: Any): UserId = email match {
    case s: String             => idsByEmail.getOrElseUpdate(s, UserId.random)
    case LaxEmailAddress(text) => idsByEmail.getOrElseUpdate(text, UserId.random)
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

  implicit class CollaboratorLaxEmailSyntax(emailAddress: LaxEmailAddress) {
    def admin()     = Collaborators.Administrator(idOf(emailAddress.text), emailAddress)
    def developer() = Collaborators.Developer(idOf(emailAddress.text), emailAddress)

    def admin(userId: UserId) = {
      idsByEmail.put(emailAddress.text, userId)
      Collaborators.Administrator(userId, emailAddress)
    }

    def developer(userId: UserId) = {
      idsByEmail.put(emailAddress.text, userId)
      Collaborators.Developer(userId, emailAddress)
    }
  }
}
