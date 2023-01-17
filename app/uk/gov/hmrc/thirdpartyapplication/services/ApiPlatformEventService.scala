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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.connector.ApiPlatformEventsConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ClientSecretAdded, ClientSecretAddedObfuscated}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{ActorHelper, HeaderCarrierHelper}

// TODO - context and version probably should be strings in the events??
@Singleton
class ApiPlatformEventService @Inject() (val apiPlatformEventsConnector: ApiPlatformEventsConnector)(implicit val ec: ExecutionContext) extends ApplicationLogger with ActorHelper {

  def applyEvents(events: NonEmptyList[UpdateApplicationEvent])(implicit hc: HeaderCarrier): Future[Boolean] = {
    events match {
      case NonEmptyList(e, Nil)  => applyEvent(e)
      case NonEmptyList(e, tail) => applyEvent(e).flatMap(_ => applyEvents(NonEmptyList.fromListUnsafe(tail)))
    }
  }

  private def obfuscateEvent(event: UpdateApplicationEvent): UpdateApplicationEvent                  = {
    event match {
      case evt: ClientSecretAdded => ClientSecretAddedObfuscated.fromClientSecretAdded(evt)
      case _                      => event
    }
  }

  private def applyEvent(event: UpdateApplicationEvent)(implicit hc: HeaderCarrier): Future[Boolean] = {
    apiPlatformEventsConnector.sendApplicationEvent(obfuscateEvent(event))
  }

  @deprecated("remove when no longer using old logic")
  def sendClientSecretAddedEvent(appData: ApplicationData, clientSecretId: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(
      appId,
      eventType = "ClientSecretAdded",
      maybeFuture = getOldActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ClientSecretAddedEvent(EventId.random, appId, actor = actor, clientSecretId = clientSecretId))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendClientSecretRemovedEvent(appData: ApplicationData, clientSecretId: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(
      appId,
      eventType = "ClientSecretRemoved",
      maybeFuture = getOldActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ClientSecretRemovedEvent(EventId.random, appId, actor = actor, clientSecretId = clientSecretId))
      }
    )
  }

  def sendTeamMemberAddedEvent(appData: ApplicationData, teamMemberEmail: String, teamMemberRole: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(
      appId,
      eventType = "TeamMemberAddedEvent",
      maybeFuture = getOldActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(TeamMemberAddedEvent(EventId.random, appId, actor = actor, teamMemberEmail = teamMemberEmail, teamMemberRole = teamMemberRole))
      }
    )
  }

  def sendTeamMemberRemovedEvent(appData: ApplicationData, teamMemberEmail: String, teamMemberRole: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(
      appId,
      eventType = "TeamMemberRemovedEvent",
      maybeFuture = getOldActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(TeamMemberRemovedEvent(EventId.random, appId, actor = actor, teamMemberEmail = teamMemberEmail, teamMemberRole = teamMemberRole))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendRedirectUrisUpdatedEvent(appData: ApplicationData, oldRedirectUris: String, newRedirectUris: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(
      appId,
      eventType = "RedirectUrisUpdatedEvent",
      maybeFuture = getOldActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(RedirectUrisUpdatedEvent(EventId.random, appId, actor = actor, oldRedirectUris = oldRedirectUris, newRedirectUris = newRedirectUris))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendApiSubscribedEvent(appData: ApplicationData, context: ApiContext, version: ApiVersion)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(
      appId,
      eventType = "ApiSubscribedEvent",
      maybeFuture = getOldActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ApiSubscribedEvent(EventId.random, appId, actor = actor, context = context.value, version = version.value))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendApiUnsubscribedEvent(appData: ApplicationData, context: ApiContext, version: ApiVersion)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val appId = appData.id.value.toString
    handleResult(
      appId,
      eventType = "ApiUnsubscribedEvent",
      maybeFuture = getOldActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ApiUnsubscribedEvent(EventId.random, appId, actor = actor, context = context.value, version = version.value))
      }
    )
  }

  private def handleResult(applicationId: String, eventType: String, maybeFuture: Option[Future[Boolean]]): Future[Boolean] = maybeFuture match {
    case Some(x) => x
    case None    =>
      logger.error(s"send $eventType for applicationId:${applicationId} not possible")
      Future.successful(false)
  }

  private def sendEvent(appEvent: ApplicationEvent)(implicit hc: HeaderCarrier): Future[Boolean] = appEvent match {
    case tmae: TeamMemberAddedEvent     => apiPlatformEventsConnector.sendTeamMemberAddedEvent(tmae)
    case tmre: TeamMemberRemovedEvent   => apiPlatformEventsConnector.sendTeamMemberRemovedEvent(tmre)
    case csae: ClientSecretAddedEvent   => apiPlatformEventsConnector.sendClientSecretAddedEvent(csae)
    case csra: ClientSecretRemovedEvent => apiPlatformEventsConnector.sendClientSecretRemovedEvent(csra)
    case ruue: RedirectUrisUpdatedEvent => apiPlatformEventsConnector.sendRedirectUrisUpdatedEvent(ruue)
    case apse: ApiSubscribedEvent       => apiPlatformEventsConnector.sendApiSubscribedEvent(apse)
    case apuse: ApiUnsubscribedEvent    => apiPlatformEventsConnector.sendApiUnsubscribedEvent(apuse)
  }
}
