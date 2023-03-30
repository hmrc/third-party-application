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
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

class ApiPlatformEventsConnectorSpec extends ConnectorSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val eventAppId        = ApplicationId.random
  val eventCollaborator = Actors.AppCollaborator("bob@bob.com".toLaxEmail)

  val teamMemberAddedEvent: TeamMemberAddedEvent = TeamMemberAddedEvent(
    id = EventId.random,
    applicationId = eventAppId,
    eventDateTime = FixedClock.instant,
    actor = eventCollaborator,
    teamMemberEmail = "teamMember@teamMember.com".toLaxEmail,
    teamMemberRole = "ADMIN"
  )

  val teamMemberRemovedEvent: TeamMemberRemovedEvent = TeamMemberRemovedEvent(
    id = EventId.random,
    applicationId = eventAppId,
    eventDateTime = FixedClock.instant,
    actor = eventCollaborator,
    teamMemberEmail = "teamMember@teamMember.com".toLaxEmail,
    teamMemberRole = "ADMIN"
  )

  val clientSecretAddedEvent: ClientSecretAddedEvent = ClientSecretAddedEvent(
    id = EventId.random,
    applicationId = eventAppId,
    eventDateTime = FixedClock.instant,
    actor = eventCollaborator,
    clientSecretId = "1234"
  )

  val clientSecretRemovedEvent: ClientSecretRemovedEvent = ClientSecretRemovedEvent(
    id = EventId.random,
    applicationId = eventAppId,
    eventDateTime = FixedClock.instant,
    actor = eventCollaborator,
    clientSecretId = "1234"
  )

  val redirectUrisUpdatedEvent: RedirectUrisUpdatedEvent = RedirectUrisUpdatedEvent(
    id = EventId.random,
    applicationId = eventAppId,
    eventDateTime = FixedClock.instant,
    actor = eventCollaborator,
    oldRedirectUris = "originalUris",
    newRedirectUris = "newRedirectUris"
  )

  val apiSubscribedEvent: ApiSubscribedEvent = ApiSubscribedEvent(
    id = EventId.random,
    applicationId = eventAppId,
    eventDateTime = FixedClock.instant,
    actor = eventCollaborator,
    context = "context",
    version = "2.0"
  )

  val apiUnSubscribedEvent: ApiUnsubscribedEvent = ApiUnsubscribedEvent(
    id = EventId.random,
    applicationId = eventAppId,
    eventDateTime = FixedClock.instant,
    actor = eventCollaborator,
    context = "context",
    version = "2.0"
  )

  val prodAppNameChangedEvent: ProductionAppNameChangedEvent = ProductionAppNameChangedEvent(
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

    def apiApplicationEventsWillReturnCreated(request: ApplicationEvent) =
      stubFor(
        post(urlMatching("/application-events/.*"))
          .withJsonRequestBody(request)
          .willReturn(
            aResponse()
              .withStatus(CREATED)
          )
      )

    def apiApplicationEventsWillFailWith(status: Int) =
      stubFor(
        post(urlMatching("/application-events/.*"))
          .willReturn(
            aResponse()
              .withStatus(status)
          )
      )

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

  "ApiPlatformEventsConnector" when {
    "ClientSecretAdded" should {
      "return true when httpclient receives CREATED status" in new Setup() {
        apiApplicationEventsWillReturnCreated(clientSecretAddedEvent)
        val result = await(underTest.sendClientSecretAddedEvent(clientSecretAddedEvent)(hc))

        result shouldBe true
      }

      "return true when connector is disabled" in new Setup(false) {
        val result = await(underTest.sendClientSecretAddedEvent(clientSecretAddedEvent)(hc))

        result shouldBe true
      }

      "return false when httpclient receives internal server error status" in new Setup() {
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendClientSecretAddedEvent(clientSecretAddedEvent)(hc))

        result shouldBe false
      }
    }

    "ClientSecretRemoved" should {
      "return true when httpclient receives CREATED status" in new Setup() {
        apiApplicationEventsWillReturnCreated(clientSecretRemovedEvent)
        val result = await(underTest.sendClientSecretRemovedEvent(clientSecretRemovedEvent)(hc))

        result shouldBe true
      }

      "return true when connector is disabled" in new Setup(false) {
        val result = await(underTest.sendClientSecretRemovedEvent(clientSecretRemovedEvent)(hc))

        result shouldBe true
      }

      "return false when httpclient receives internal server error status" in new Setup() {
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendClientSecretRemovedEvent(clientSecretRemovedEvent)(hc))

        result shouldBe false
      }
    }

    "RedirectUrisUpdatedEvent" should {
      "return true when httpclient receives CREATED status" in new Setup() {
        apiApplicationEventsWillReturnCreated(redirectUrisUpdatedEvent)
        val result = await(underTest.sendRedirectUrisUpdatedEvent(redirectUrisUpdatedEvent)(hc))

        result shouldBe true
      }

      "return true when connector is disabled" in new Setup(false) {
        val result = await(underTest.sendRedirectUrisUpdatedEvent(redirectUrisUpdatedEvent)(hc))

        result shouldBe true
      }

      "return false when httpclient receives internal server error status" in new Setup() {
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendRedirectUrisUpdatedEvent(redirectUrisUpdatedEvent)(hc))

        result shouldBe false
      }
    }

    "ApiSubscribedEvent" should {
      "return true when httpclient receives CREATED status" in new Setup() {
        apiApplicationEventsWillReturnCreated(apiSubscribedEvent)
        val result = await(underTest.sendApiSubscribedEvent(apiSubscribedEvent)(hc))

        result shouldBe true
      }

      "return true when connector is disabled" in new Setup(false) {
        val result = await(underTest.sendApiSubscribedEvent(apiSubscribedEvent)(hc))

        result shouldBe true
      }

      "return false when httpclient receives internal server error status" in new Setup() {
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendApiSubscribedEvent(apiSubscribedEvent)(hc))

        result shouldBe false
      }
    }

    "ApiUnsubscribedEvent" should {
      "return true when httpclient receives CREATED status" in new Setup() {
        apiApplicationEventsWillReturnCreated(apiUnSubscribedEvent)
        val result = await(underTest.sendApiUnsubscribedEvent(apiUnSubscribedEvent)(hc))

        result shouldBe true
      }

      "return true when connector is disabled" in new Setup(false) {
        val result = await(underTest.sendApiUnsubscribedEvent(apiUnSubscribedEvent)(hc))

        result shouldBe true
      }

      "return false when httpclient receives internal server error status" in new Setup() {
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendApiUnsubscribedEvent(apiUnSubscribedEvent)(hc))

        result shouldBe false
      }
    }

    "ProdAppNameChangeEvent" should {
      "return true when httpclient receives CREATED status" in new Setup() {
        apiApplicationEventWillReturnCreated(prodAppNameChangedEvent)
        val result = await(underTest.sendApplicationEvent(prodAppNameChangedEvent)(hc))

        result shouldBe true
      }

      "return true when connector is disabled" in new Setup(false) {
        val result = await(underTest.sendApplicationEvent(prodAppNameChangedEvent)(hc))

        result shouldBe true
      }

      "return false when httpclient receives internal server error status" in new Setup() {
        apiApplicationEventWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendApplicationEvent(prodAppNameChangedEvent)(hc))

        result shouldBe false
      }
    }
  }
}
