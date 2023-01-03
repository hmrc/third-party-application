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

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{Actor, CollaboratorActor, GatekeeperUserActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ActorType, Collaborator, OldActor}

trait ActorHelper {

  @deprecated("use getActorFromContext instead")
  def getOldActorFromContext(userContext: Map[String, String], collaborators: Set[Collaborator]): Option[OldActor] = {
    if (userContext.isEmpty) {
      Option(OldActor("admin@gatekeeper", ActorType.GATEKEEPER))
    } else {
      userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
        .map(email => OldActor(email, deriveOldActorType(email, collaborators)))
    }
  }

  private def deriveOldActorType(userEmail: String, collaborators: Set[Collaborator]): ActorType.Value =
    collaborators
      .find(_.emailAddress.equalsIgnoreCase(userEmail)).fold(ActorType.GATEKEEPER) { _: Collaborator => ActorType.COLLABORATOR }


  def getActorFromContext(userContext: Map[String, String], collaborators: Set[Collaborator]): Actor =
    userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
      .map(email => deriveActor(email, collaborators))
      .getOrElse(GatekeeperUserActor("Gatekeeper Admin"))

  private def deriveActor(userEmail: String, collaborators: Set[Collaborator]): Actor =
    collaborators.find(_.emailAddress.equalsIgnoreCase(userEmail)) match {
      case None                  => GatekeeperUserActor("Gatekeeper Admin")
      case Some(_: Collaborator) => CollaboratorActor(userEmail)
    }

}
