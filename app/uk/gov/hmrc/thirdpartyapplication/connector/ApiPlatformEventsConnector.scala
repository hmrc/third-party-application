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

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.services.EventsInterServiceCallJsonFormatters._

object ApiPlatformEventsConnector {
  case class Config(baseUrl: String, enabled: Boolean)
}

class ApiPlatformEventsConnector @Inject() (http: HttpClient, config: ApiPlatformEventsConnector.Config)(implicit val ec: ExecutionContext) extends ResponseUtils
    with ApplicationLogger {

  val serviceBaseUrl: String      = s"${config.baseUrl}"
  private val applicationEventUri = "/application-event"

  def sendApplicationEvent(event: ApplicationEvent)(implicit hc: HeaderCarrier): Future[Boolean] = postEvent(event, applicationEventUri)(hc)

  private def postEvent(event: ApplicationEvent, uri: String)(hc: HeaderCarrier): Future[Boolean] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc.copy(authorization = None)

    if (config.enabled) {
      http.POST[ApplicationEvent, ErrorOr[Unit]](
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
