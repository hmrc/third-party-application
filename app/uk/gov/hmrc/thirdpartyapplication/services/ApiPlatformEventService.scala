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
import uk.gov.hmrc.thirdpartyapplication.models.{Actor, ActorType, ApiSubscribedEvent, ApiUnsubscribedEvent, ApplicationEvent, ClientSecretAddedEvent, ClientSecretRemovedEvent, Collaborator, EventId, RedirectUrisUpdatedEvent, TeamMemberAddedEvent, TeamMemberRemovedEvent}
import uk.gov.hmrc.thirdpartyapplication.util.HeaderCarrierHelper

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiPlatformEventService @Inject()(val apiPlatformEventsConnector: ApiPlatformEventsConnector)(implicit val ec: ExecutionContext) {

  def sendClientSecretAddedEvent(appData: ApplicationData, clientSecretId: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(appId, eventType = "ClientSecretAdded",
      maybeFuture = userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ClientSecretAddedEvent(EventId.random, appId, actor = actor, clientSecretId = clientSecretId))
      })
  }

  def sendClientSecretRemovedEvent(appData: ApplicationData, clientSecretId: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(appId, eventType = "ClientSecretRemoved",
      maybeFuture = userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ClientSecretRemovedEvent(EventId.random, appId, actor = actor, clientSecretId = clientSecretId))
      })
  }

  def sendTeamMemberAddedEvent(appData: ApplicationData, teamMemberEmail: String, teamMemberRole: String)
                              (implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(appId, eventType = "TeamMemberAddedEvent",
      maybeFuture = userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(TeamMemberAddedEvent(EventId.random, appId, actor = actor, teamMemberEmail = teamMemberEmail, teamMemberRole = teamMemberRole))
      })
  }

  def sendTeamMemberRemovedEvent(appData: ApplicationData, teamMemberEmail: String, teamMemberRole: String)
                                (implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(appId, eventType = "TeamMemberRemovedEvent",
      maybeFuture = userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(TeamMemberRemovedEvent(EventId.random, appId, actor = actor, teamMemberEmail = teamMemberEmail, teamMemberRole = teamMemberRole))
      })
  }

  def sendRedirectUrisUpdatedEvent(appData: ApplicationData, oldRedirectUris: String, newRedirectUris: String)
                                (implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(appId, eventType = "RedirectUrisUpdatedEvent",
      maybeFuture = userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(RedirectUrisUpdatedEvent(EventId.random, appId, actor = actor, oldRedirectUris = oldRedirectUris, newRedirectUris = newRedirectUris))
      })
  }

  def sendApiSubscribedEvent(appData: ApplicationData, context: String, version: String)
                                  (implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(appId, eventType = "ApiSubscribedEvent",
      maybeFuture = userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ApiSubscribedEvent(EventId.random, appId, actor = actor, context = context, version = version))
      })
  }


  def sendApiUnsubscribedEvent(appData: ApplicationData, context: String, version: String)
                            (implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(appId, eventType = "ApiUnsubscribedEvent",
      maybeFuture = userContextToActor(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ApiUnsubscribedEvent(EventId.random, appId, actor = actor, context = context, version = version))
      })
  }

  private def handleResult(applicationId: String, eventType: String, maybeFuture: Option[Future[Boolean]]): Future[Boolean] = maybeFuture match {
    case Some(x) => x
    case None =>
      Logger.error(s"send $eventType for applicationId:${applicationId} not possible")
      Future.successful(false)
  }

  private def sendEvent(appEvent: ApplicationEvent)(implicit hc: HeaderCarrier): Future[Boolean] = appEvent match {
    case tmae: TeamMemberAddedEvent => apiPlatformEventsConnector.sendTeamMemberAddedEvent(tmae)
    case tmre: TeamMemberRemovedEvent => apiPlatformEventsConnector.sendTeamMemberRemovedEvent(tmre)
    case csae: ClientSecretAddedEvent => apiPlatformEventsConnector.sendClientSecretAddedEvent(csae)
    case csra: ClientSecretRemovedEvent => apiPlatformEventsConnector.sendClientSecretRemovedEvent(csra)
    case ruue: RedirectUrisUpdatedEvent => apiPlatformEventsConnector.sendRedirectUrisUpdatedEvent(ruue)
    case apse: ApiSubscribedEvent => apiPlatformEventsConnector.sendApiSubscribedEvent(apse)
    case apuse: ApiUnsubscribedEvent => apiPlatformEventsConnector.sendApiUnsubscribedEvent(apuse)
  }

  private def userContextToActor(userContext: Map[String, String], collaborators: Set[Collaborator]): Option[Actor] = {
    if (userContext.isEmpty) {
      Option(Actor("admin@gatekeeper", ActorType.GATEKEEPER))
    } else {
      userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
        .map(email => Actor(email, deriveActorType(email, collaborators)))
    }
  }

  private def deriveActorType(userEmail: String, collaborators: Set[Collaborator]): ActorType.Value =
    collaborators
      .find(_.emailAddress.equalsIgnoreCase(userEmail)).fold(ActorType.GATEKEEPER) { _: Collaborator => ActorType.COLLABORATOR }
}
