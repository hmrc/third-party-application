/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.ApiPlatformEventsConnector
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{Actor, ActorType, Collaborator, TeamMemberAddedEvent}
import uk.gov.hmrc.thirdpartyapplication.util.HeaderCarrierHelper

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiPlatformEventService @Inject()(val apiPlatformEventsConnector: ApiPlatformEventsConnector)(implicit val ec: ExecutionContext) {

  def sendTeamMemberAddedEvent(appData: ApplicationData, teamMemberEmail: String, teamMemberRole: String)
                              (implicit hc: HeaderCarrier): Future[Boolean] ={

    val appId = appData.id.toString
    userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map(actor =>
      apiPlatformEventsConnector.sendTeamMemberAddedEvent(TeamMemberAddedEvent(applicationId = appId,
        actor = actor,
        teamMemberEmail = teamMemberEmail,
        teamMemberRole = teamMemberRole))(hc)
    ) match {
      case Some(x) => x
      case None =>
        Logger.error(s"send teamMemberAddedEvent for applicationId:$appId not possible")
        Future.successful(false)
    }

  }

  private def userContextToActor(userContext: Map[String, String], collaborators: Set[Collaborator]): Option[Actor] ={
    userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
      .map(email=> Actor(email, deriveActorType(email, collaborators)))
  }

  private def deriveActorType(userEmail: String,  collaborators: Set[Collaborator]): ActorType.Value =
    collaborators
      .find(_.emailAddress.equalsIgnoreCase(userEmail)).fold(ActorType.GATEKEEPER){_: Collaborator => ActorType.COLLABORATOR}
}
