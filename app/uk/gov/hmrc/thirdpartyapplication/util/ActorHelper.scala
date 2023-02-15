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
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.OldStyleActor
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.OldStyleActors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actor
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors

trait ActorHelper {

  @deprecated("use getActorFromContext instead")
  def getOldActorFromContext(userContext: Map[String, String], collaborators: Set[Collaborator]): Option[OldStyleActor] = {
    if (userContext.isEmpty) {
      Some(OldStyleActors.GatekeeperUser("admin@gatekeeper"))
    } else {
    userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
        .map(userKey =>deriveOldActorType(userKey, collaborators))
    }
  }

  private def deriveOldActorType(userKey: String, collaborators: Set[Collaborator]): OldStyleActor =
    collaborators
      .find(_.emailAddress.equalsIgnoreCase(LaxEmailAddress(userKey))).fold[OldStyleActor](OldStyleActors.GatekeeperUser(userKey))(_ => OldStyleActors.Collaborator(userKey))


  def getActorFromContext(userContext: Map[String, String], collaborators: Set[Collaborator]): Actor =
    userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
      .map(email => deriveActor(LaxEmailAddress(email), collaborators))
      .getOrElse(Actors.GatekeeperUser("Gatekeeper Admin"))

  private def deriveActor(email: LaxEmailAddress, collaborators: Set[Collaborator]): Actor =
    collaborators.find(_.emailAddress.equalsIgnoreCase(email)) match {
      case None                  => Actors.GatekeeperUser("Gatekeeper Admin")
      case Some(_: Collaborator) => Actors.AppCollaborator(email)
    }

}
