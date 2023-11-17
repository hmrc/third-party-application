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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborator

trait ActorHelper {

  def getActorFromContext(userContext: Map[String, String], collaborators: Set[Collaborator]): Option[Actor] =
    if (userContext.isEmpty) {
      Some(Actors.GatekeeperUser("Gatekeeper Admin")) // Should be Unknown ???
    } else {
      userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
        .map(emailOrGKUser => deriveActor(emailOrGKUser, collaborators))
    }

  private def deriveActor(emailOrGKUser: String, collaborators: Set[Collaborator]): Actor = {
    val possiblyEmail = LaxEmailAddress(emailOrGKUser)
    collaborators.find(_.emailAddress.equalsIgnoreCase(possiblyEmail)) match {
      case None                  => Actors.GatekeeperUser(emailOrGKUser)
      case Some(_: Collaborator) => Actors.AppCollaborator(possiblyEmail)
    }
  }
}
