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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}

trait ActorTestData extends EmailTestData {

  implicit class ActorStringSyntax(userName: String) {
    def actor() = Actors.GatekeeperUser(userName)
  }

  implicit class ActorLaxEmailSyntax(email: LaxEmailAddress) {
    def actor() = Actors.AppCollaborator(email)
  }

  implicit class ActorCollaboratorSyntax(collaborator: Collaborator) {
    def actor() = Actors.AppCollaborator(collaborator.emailAddress)
  }

  val loggedInAsActor   = loggedInUser.actor()
  val otherAdminAsActor = anAdminEmail.actor()
  val developerAsActor  = devEmail.actor()

  val gatekeeperUser = "Bob from SDST"

  val gatekeeperActor = Actors.GatekeeperUser(gatekeeperUser)
}