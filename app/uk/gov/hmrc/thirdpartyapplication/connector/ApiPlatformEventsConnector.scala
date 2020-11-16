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

package uk.gov.hmrc.thirdpartyapplication.connector

import javax.inject.Inject
import play.api.Logger
import play.api.http.ContentTypes.JSON
import play.api.http.HeaderNames.CONTENT_TYPE
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationEventFormats.formatApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.models.{ApiSubscribedEvent, ApiUnsubscribedEvent, ApplicationEvent, ClientSecretAddedEvent, ClientSecretRemovedEvent, RedirectUrisUpdatedEvent, TeamMemberAddedEvent, TeamMemberRemovedEvent}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.UpstreamErrorResponse

class ApiPlatformEventsConnector @Inject()(http: HttpClient, config: ApiPlatformEventsConfig)
                                          (implicit val ec: ExecutionContext) {

  val serviceBaseUrl: String = s"${config.baseUrl}"
  private val applicationEventsUri = "/application-events"
  private val teamMemberAddedUri = "/teamMemberAdded"
  private val teamMemberRemovedUri = "/teamMemberRemoved"
  private val clientSecretAddedUri = "/clientSecretAdded"
  private val clientSecretRemovedUri = "/clientSecretRemoved"
  private val redirectUrisUpdatedUri = "/redirectUrisUpdated"
  private val apiSubscribedUri = "/apiSubscribed"
  private val apiUnsubscribedUri = "/apiUnsubscribed"

  def sendRedirectUrisUpdatedEvent(event: RedirectUrisUpdatedEvent)
                                  (implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, redirectUrisUpdatedUri)(hc)

  def sendTeamMemberAddedEvent(event: TeamMemberAddedEvent)
                              (implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, teamMemberAddedUri)(hc)

  def sendTeamMemberRemovedEvent(event: TeamMemberRemovedEvent)
                                (implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, teamMemberRemovedUri)(hc)

  def sendClientSecretAddedEvent(event: ClientSecretAddedEvent)
                                (implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, clientSecretAddedUri)(hc)

  def sendClientSecretRemovedEvent(event: ClientSecretRemovedEvent)
                                  (implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, clientSecretRemovedUri)(hc)

  def sendApiSubscribedEvent(event: ApiSubscribedEvent)
                            (implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, apiSubscribedUri)(hc)

  def sendApiUnsubscribedEvent(event: ApiUnsubscribedEvent)
                              (implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, apiUnsubscribedUri)(hc)

  private def postEvent(event: ApplicationEvent, uri: String)(hc: HeaderCarrier): Future[Boolean] = {

    import uk.gov.hmrc.http.HttpReads.Implicits._   
    implicit val headersWithoutAuthorization: HeaderCarrier = hc
      .copy(authorization = None)
      .withExtraHeaders(CONTENT_TYPE -> JSON)
    if (config.enabled) {
      http.POST[ApplicationEvent, Unit](
        addEventURI(uri),
        event
      ).map(_ => {
        Logger.info(s"calling platform event service for application ${event.applicationId}")
        true
      })
    } else {
      Logger.info("call to platform events disabled")
      Future.successful(true)
    }
  }

  private def addEventURI(path: String): String = {
    serviceBaseUrl + applicationEventsUri + path
  }
}

case class ApiPlatformEventsConfig(baseUrl: String, enabled: Boolean)
