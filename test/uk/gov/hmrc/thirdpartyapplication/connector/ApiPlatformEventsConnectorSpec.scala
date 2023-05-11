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

import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._

import play.api.http.Status._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._

class ApiPlatformEventsConnectorSpec extends ConnectorSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val eventAppId        = ApplicationId.random
  val eventCollaborator = Actors.AppCollaborator("bob@bob.com".toLaxEmail)

  val exampleEvent: ProductionAppNameChangedEvent = ProductionAppNameChangedEvent(
    id = EventId.random,
    applicationId = ApplicationId.random,
    eventDateTime = FixedClock.instant,
    actor = Actors.GatekeeperUser("mr gatekeeper"),
    oldAppName = "old name",
    newAppName = "new name",
    requestingAdminEmail = "admin@example.com".toLaxEmail
  )

  abstract class Setup(enabled: Boolean = true) {
    import uk.gov.hmrc.apiplatform.modules.events.applications.domain.services.EventsInterServiceCallJsonFormatters._

    val http: HttpClient = app.injector.instanceOf[HttpClient]

    val config: ApiPlatformEventsConnector.Config = ApiPlatformEventsConnector.Config(wireMockUrl, enabled)

    val underTest = new ApiPlatformEventsConnector(http, config)

    def apiApplicationEventWillReturnCreated(request: ApplicationEvent) =
      stubFor(
        post(urlEqualTo("/application-event"))
          .withJsonRequestBody(request)
          .willReturn(
            aResponse()
              .withStatus(CREATED)
          )
      )

    def apiApplicationEventWillFailWith(status: Int) =
      stubFor(
        post(urlEqualTo("/application-event"))
          .willReturn(
            aResponse()
              .withStatus(status)
          )
      )
  }

  "ApiPlatformEventsConnector" should {
    "return true when httpclient receives CREATED status" in new Setup() {
      apiApplicationEventWillReturnCreated(exampleEvent)
      val result = await(underTest.sendApplicationEvent(exampleEvent)(hc))

      result shouldBe true
    }

    "return true when connector is disabled" in new Setup(false) {
      val result = await(underTest.sendApplicationEvent(exampleEvent)(hc))

      result shouldBe true
    }

    "return false when httpclient receives internal server error status" in new Setup() {
      apiApplicationEventWillFailWith(INTERNAL_SERVER_ERROR)
      val result = await(underTest.sendApplicationEvent(exampleEvent)(hc))

      result shouldBe false
    }
  }
}
