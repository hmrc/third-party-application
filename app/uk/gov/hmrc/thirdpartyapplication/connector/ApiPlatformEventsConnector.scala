/*
 * Copyright 2022 HM Revenue & Customs
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

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationEventFormats.formatApplicationEvent
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent

object ApiPlatformEventsConnector {
  case class Config(baseUrl: String, enabled: Boolean)
}

class ApiPlatformEventsConnector @Inject() (http: HttpClient, config: ApiPlatformEventsConnector.Config)(implicit val ec: ExecutionContext) extends ResponseUtils
    with ApplicationLogger {

  val serviceBaseUrl: String         = s"${config.baseUrl}"
  private val applicationEventsUri   = "/application-events"
  private val teamMemberAddedUri     = applicationEventsUri + "/teamMemberAdded"
  private val teamMemberRemovedUri   = applicationEventsUri + "/teamMemberRemoved"
  private val clientSecretAddedUri   = applicationEventsUri + "/clientSecretAdded"
  private val clientSecretRemovedUri = applicationEventsUri + "/clientSecretRemoved"
  private val redirectUrisUpdatedUri = applicationEventsUri + "/redirectUrisUpdated"
  private val apiSubscribedUri       = applicationEventsUri + "/apiSubscribed"
  private val apiUnsubscribedUri     = applicationEventsUri + "/apiUnsubscribed"
  private val updateApplicationUri   = "/application-event"

  def sendRedirectUrisUpdatedEvent(event: RedirectUrisUpdatedEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, redirectUrisUpdatedUri)(hc)

  def sendTeamMemberAddedEvent(event: TeamMemberAddedEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, teamMemberAddedUri)(hc)

  def sendTeamMemberRemovedEvent(event: TeamMemberRemovedEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, teamMemberRemovedUri)(hc)

  @deprecated("remove after client is no longer using the old endpoint")
  def sendClientSecretAddedEvent(event: ClientSecretAddedEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, clientSecretAddedUri)(hc)

  @deprecated("remove after client is no longer using the old endpoint")
  def sendClientSecretRemovedEvent(event: ClientSecretRemovedEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, clientSecretRemovedUri)(hc)

  def sendApiSubscribedEvent(event: ApiSubscribedEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, apiSubscribedUri)(hc)

  def sendApiUnsubscribedEvent(event: ApiUnsubscribedEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, apiUnsubscribedUri)(hc)

  private def postEvent(event: ApplicationEvent, uri: String)(hc: HeaderCarrier): Future[Boolean] = {

    implicit val headersWithoutAuthorization: HeaderCarrier = hc
      .copy(authorization = None)

    if (config.enabled) {
      http.POST[ApplicationEvent, ErrorOr[Unit]](
        addEventURI(uri),
        event
      ).map {
        case Right(_) =>
          logger.info(s"calling platform event service for application ${event.applicationId}")
          true
        case Left(e)  =>
          logger.warn(s"calling platform event service failed for application ${event.applicationId} $e")
          false
      }
    } else {
      logger.info("call to platform events disabled")
      Future.successful(true)
    }
  }

  def sendApplicationEvent(event: UpdateApplicationEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, updateApplicationUri)(hc)

  private def postEvent(event: UpdateApplicationEvent, uri: String)(hc: HeaderCarrier): Future[Boolean] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc.copy(authorization = None)

    if (config.enabled) {
      http.POST[UpdateApplicationEvent, ErrorOr[Unit]](
        addEventURI(uri),
        event
      ).map {
        case Right(_) =>
          logger.info(s"calling platform event service for application ${event.applicationId.value}")
          true
        case Left(e)  =>
          logger.warn(s"calling platform event service failed for application ${event.applicationId.value} $e")
          false
      }
    } else {
      logger.info("call to platform events disabled")
      Future.successful(true)
    }
  }
  private def addEventURI(path: String): String = {
    serviceBaseUrl + path
  }
}
