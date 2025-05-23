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

package uk.gov.hmrc.thirdpartyapplication.connector

import java.net.URL
import java.time.Instant
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}

import uk.gov.hmrc.apiplatform.modules.common.connectors.ResponseUtils
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.services.EventsInterServiceCallJsonFormatters._

case class DisplayEvent(
    applicationId: ApplicationId,
    eventDateTime: Instant,
    actor: Actor,
    eventTagDescription: String,
    eventType: String,
    metaData: List[String]
  )

object DisplayEvent {
  implicit val format: OFormat[DisplayEvent] = Json.format[DisplayEvent]
}

case class QueryResponse(events: List[DisplayEvent])

object QueryResponse {
  implicit val format: OFormat[QueryResponse] = Json.format[QueryResponse]
}

object ApiPlatformEventsConnector {
  case class Config(baseUrl: String, enabled: Boolean)
}

class ApiPlatformEventsConnector @Inject() (http: HttpClientV2, config: ApiPlatformEventsConnector.Config)(implicit val ec: ExecutionContext) extends ResponseUtils
    with ApplicationLogger {

  val serviceBaseUrl: String      = s"${config.baseUrl}"
  private val applicationEventUri = "/application-event"

  def sendApplicationEvent(event: ApplicationEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, applicationEventUri)(hc)

  private def postEvent(event: ApplicationEvent, uri: String)(hc: HeaderCarrier): Future[Boolean] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc.copy(authorization = None)

    if (config.enabled) {
      http.post(eventURI(uri))
        .withBody(Json.toJson(event))
        .execute[ErrorOr[Unit]]
        .map {
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

  private def eventURI(path: String): URL = {
    val x = s"$serviceBaseUrl$path"
    url"$x"
  }

  def query(appId: ApplicationId, tag: Option[String], actorType: Option[String])(implicit hc: HeaderCarrier): Future[List[DisplayEvent]] = {
    val queryParams =
      Seq(
        tag.map(et => "eventTag" -> et),
        actorType.map(at => "actorType" -> at)
      ).collect {
        case Some((a, b)) => a -> b
      }

    http.get(url"${eventURI(applicationEventUri)}/${appId}?$queryParams").execute[Option[QueryResponse]]
      .map {
        case None           => List.empty
        case Some(response) => response.events
      }
  }
}
