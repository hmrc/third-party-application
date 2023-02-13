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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborators
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import scala.collection.mutable

trait CollaboratorTestData {

  val idsByEmail = mutable.Map[String, UserId]()

  def idOf(email: String) = {
    idsByEmail.getOrElseUpdate(email, UserId.random)
  }

  implicit class CollaboratorStringSyntax(emailAddress: String) {
    def admin() = Collaborators.Administrator(idOf(emailAddress), emailAddress)
    def developer() = Collaborators.Developer(idOf(emailAddress), emailAddress)

    def admin(userId: UserId) = {
      idsByEmail.put(emailAddress, userId)
      Collaborators.Administrator(userId, emailAddress)
    }
    
    def developer(userId: UserId) = {
      idsByEmail.put(emailAddress, userId)
      Collaborators.Developer(userId, emailAddress)
    }
  }
}
