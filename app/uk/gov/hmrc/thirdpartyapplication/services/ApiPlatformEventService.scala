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

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.connector.ApiPlatformEventsConnector
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{ActorHelper, HeaderCarrierHelper}

@Singleton
class ApiPlatformEventService @Inject() (val apiPlatformEventsConnector: ApiPlatformEventsConnector, clock: Clock)(implicit val ec: ExecutionContext) extends ApplicationLogger
    with ActorHelper {

  def applyEvents(events: NonEmptyList[ApplicationEvent])(implicit hc: HeaderCarrier): Future[Boolean] = {
    events match {
      case NonEmptyList(e, Nil)  => applyEvent(e)
      case NonEmptyList(e, tail) => applyEvent(e).flatMap(_ => applyEvents(NonEmptyList.fromListUnsafe(tail)))
    }
  }

  private def applyEvent(event: ApplicationEvent)(implicit hc: HeaderCarrier): Future[Boolean] = {
    apiPlatformEventsConnector.sendApplicationEvent(event)
  }

  @deprecated("remove when no longer using old logic")
  def sendClientSecretAddedEvent(appData: ApplicationData, clientSecretId: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    handleResult(
      appData.id,
      eventType = "ClientSecretAdded",
      maybeFuture = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ClientSecretAddedEvent(EventId.random, appData.id, Instant.now(clock), actor = actor, clientSecretId = clientSecretId))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendClientSecretRemovedEvent(appData: ApplicationData, clientSecretId: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    handleResult(
      appData.id,
      eventType = "ClientSecretRemoved",
      maybeFuture = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ClientSecretRemovedEvent(EventId.random, appData.id, Instant.now(clock), actor = actor, clientSecretId = clientSecretId))
      }
    )
  }

  def sendTeamMemberAddedEvent(appData: ApplicationData, teamMemberEmail: LaxEmailAddress, teamMemberRole: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    handleResult(
      appData.id,
      eventType = "TeamMemberAddedEvent",
      maybeFuture = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(TeamMemberAddedEvent(EventId.random, appData.id, Instant.now(clock), actor = actor, teamMemberEmail = teamMemberEmail, teamMemberRole = teamMemberRole))
      }
    )
  }

  def sendTeamMemberRemovedEvent(appData: ApplicationData, teamMemberEmail: LaxEmailAddress, teamMemberRole: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    handleResult(
      appData.id,
      eventType = "TeamMemberRemovedEvent",
      maybeFuture = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(TeamMemberRemovedEvent(EventId.random, appData.id, Instant.now(clock), actor = actor, teamMemberEmail = teamMemberEmail, teamMemberRole = teamMemberRole))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendRedirectUrisUpdatedEvent(appData: ApplicationData, oldRedirectUris: String, newRedirectUris: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    handleResult(
      appData.id,
      eventType = "RedirectUrisUpdatedEvent",
      maybeFuture = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor =>
          sendEvent(RedirectUrisUpdatedEvent(EventId.random, appData.id, Instant.now(clock), actor = actor, oldRedirectUris = oldRedirectUris, newRedirectUris = newRedirectUris))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendApiSubscribedEvent(appData: ApplicationData, context: ApiContext, version: ApiVersion)(implicit hc: HeaderCarrier): Future[Boolean] = {
    handleResult(
      appData.id,
      eventType = "ApiSubscribedEvent",
      maybeFuture = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ApiSubscribedEvent(EventId.random, appData.id, Instant.now(clock), actor = actor, context = context.value, version = version.value))
      }
    )
  }

  @deprecated("remove when no longer using old logic")
  def sendApiUnsubscribedEvent(appData: ApplicationData, context: ApiContext, version: ApiVersion)(implicit hc: HeaderCarrier): Future[Boolean] = {
    handleResult(
      appData.id,
      eventType = "ApiUnsubscribedEvent",
      maybeFuture = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), appData.collaborators).map {
        actor => sendEvent(ApiUnsubscribedEvent(EventId.random, appData.id, Instant.now(clock), actor = actor, context = context.value, version = version.value))
      }
    )
  }

  private def handleResult(applicationId: ApplicationId, eventType: String, maybeFuture: Option[Future[Boolean]]): Future[Boolean] = maybeFuture match {
    case Some(x) => x
    case None    =>
      logger.error(s"send $eventType for applicationId:${applicationId.value} not possible")
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
    case _                              => Future.failed(new IllegalArgumentException("Bad Event in old sendEvent"))
  }
}
