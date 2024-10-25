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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.CollaboratorFixtures

trait ActorTestData extends CollaboratorFixtures {

  implicit class ActorLaxEmailSyntax(email: LaxEmailAddress) {
    def actor() = Actors.AppCollaborator(email)
  }

  val loggedInAsActor   = adminTwo.emailAddress.actor()
  val otherAdminAsActor = adminOne.emailAddress.actor()
  val developerAsActor  = developerOne.emailAddress.actor()

  val gatekeeperUser = "Bob from SDST"

  val gatekeeperActor = Actors.GatekeeperUser(gatekeeperUser)
}
