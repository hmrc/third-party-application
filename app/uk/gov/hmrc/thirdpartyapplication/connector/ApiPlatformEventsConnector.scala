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
import uk.gov.hmrc.http.{HeaderCarrier, InternalServerException}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationEventFormats.formatApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.models.TeamMemberAddedEvent

import scala.concurrent.{ExecutionContext, Future}

class ApiPlatformEventsConnector @Inject()(http: HttpClient, config: ApiPlatformEventsConfig)
                                          (implicit val ec: ExecutionContext) {

  val serviceBaseUrl: String = s"${config.baseUrl}"
  private val applicationEventsUri = "/application-events"
  private val teamMemberAddedUri = "/teamMemberAdded"

  def sendTeamMemberAddedEvent(event: TeamMemberAddedEvent)(hc: HeaderCarrier): Future[Boolean] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc
      .copy(authorization = None)
      .withExtraHeaders(CONTENT_TYPE -> JSON)
      if(config.enabled) {
        http.POST(
          addEventURI(teamMemberAddedUri),
          event
        ).map(_ => {
          Logger.info(s"calling platform event service for application ${event.applicationId}")
          true
        })
      }else{
        Logger.info("call to platform events disabled")
        Future.successful(true)
      }

  }

  def addEventURI(path: String): String = {
    serviceBaseUrl + applicationEventsUri + path
  }
}

case class ApiPlatformEventsConfig(baseUrl: String, enabled: Boolean)