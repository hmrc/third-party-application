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

import org.mockito.stubbing.ScalaOngoingStubbing
import org.scalatest.concurrent.ScalaFutures
import play.api.http.Status.{INTERNAL_SERVER_ERROR}
import uk.gov.hmrc.http.{HeaderCarrier}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.http.UpstreamErrorResponse

class ApiPlatformEventsConnectorSpec extends ConnectorSpec with ScalaFutures {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val baseUrl = s"http://example.com"

  val teamMemberAddedEvent: TeamMemberAddedEvent = TeamMemberAddedEvent(
    id = EventId.random,
    applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    teamMemberEmail = "teamMember@teamMember.com",
    teamMemberRole = "ADMIN")

  val teamMemberRemovedEvent: TeamMemberRemovedEvent = TeamMemberRemovedEvent(
    id = EventId.random,
    applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    teamMemberEmail = "teamMember@teamMember.com",
    teamMemberRole = "ADMIN")

  val clientSecretAddedEvent: ClientSecretAddedEvent = ClientSecretAddedEvent(
    id = EventId.random,
    applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    clientSecretId = "1234")

  val clientSecretRemovedEvent: ClientSecretRemovedEvent = ClientSecretRemovedEvent(
    id = EventId.random,
    applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    clientSecretId = "1234")

  val redirectUrisUpdatedEvent: RedirectUrisUpdatedEvent = RedirectUrisUpdatedEvent(
    id = EventId.random,
    applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    oldRedirectUris = "originalUris",
    newRedirectUris = "newRedirectUris")

  val apiSubscribedEvent: ApiSubscribedEvent = ApiSubscribedEvent(
    id = EventId.random,
    applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    context = "context",
    version = "2.0")

  val apiUnSubscribedEvent: ApiUnsubscribedEvent = ApiUnsubscribedEvent(
    id = EventId.random,
    applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    context = "context",
    version = "2.0")

  trait Setup {
    val mockHttpClient: HttpClient = mock[HttpClient]
    val mockConfig: ApiPlatformEventsConfig = mock[ApiPlatformEventsConfig](withSettings.lenient())

    val underTest = new ApiPlatformEventsConnector(mockHttpClient, mockConfig)

    def apiApplicationEventsWillReturnCreated = when(mockHttpClient.POST[ApplicationEvent, Unit](*, *, *)(*, *, *, *)).thenReturn(Future.successful(()))
    def apiApplicationEventsWillFailWith(status: Int) = when(mockHttpClient.POST[ApplicationEvent, Unit](*, *, *)(*, *, *, *)).thenReturn(Future.failed(UpstreamErrorResponse("Bang",status)))
    
    def configSetUpWith(enabled: Boolean): ScalaOngoingStubbing[String] ={
      when(mockConfig.enabled).thenReturn(enabled)
      when(mockConfig.baseUrl).thenReturn(baseUrl)
    }
  }

  "ApiPlatformEventsConnector" when {

    "TeamMemberAddedEvents" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturnCreated
        val result = await(underTest.sendTeamMemberAddedEvent(teamMemberAddedEvent)(hc))

        result shouldBe true
      }

      "should return true when connector is disabled" in new Setup() {
        configSetUpWith(enabled = false)
        val result = await(underTest.sendTeamMemberAddedEvent(teamMemberAddedEvent)(hc))

        result shouldBe true
      }

      "should return false when httpclient receives internal server error status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendTeamMemberAddedEvent(teamMemberAddedEvent)(hc))

        result shouldBe false
      }
    }

    "TeamMemberRemovedEvents" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturnCreated
        val result = await(underTest.sendTeamMemberRemovedEvent(teamMemberRemovedEvent)(hc))

        result shouldBe true
      }

      "should return true when connector is disabled" in new Setup() {
        configSetUpWith(enabled = false)
        val result = await(underTest.sendTeamMemberRemovedEvent(teamMemberRemovedEvent)(hc))

        result shouldBe true
      }

      "should return false when httpclient receives internal server error status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendTeamMemberRemovedEvent(teamMemberRemovedEvent)(hc))

        result shouldBe false
      }
    }


    "ClientSecretAdded" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturnCreated
        val result = await(underTest.sendClientSecretAddedEvent(clientSecretAddedEvent)(hc))

        result shouldBe true
      }

      "should return true when connector is disabled" in new Setup() {
        configSetUpWith(enabled = false)
        val result = await(underTest.sendClientSecretAddedEvent(clientSecretAddedEvent)(hc))

        result shouldBe true
      }

      "should return false when httpclient receives internal server error status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendClientSecretAddedEvent(clientSecretAddedEvent)(hc))

        result shouldBe false
      }
    }

    "ClientSecretRemoved" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturnCreated
        val result = await(underTest.sendClientSecretRemovedEvent(clientSecretRemovedEvent)(hc))

        result shouldBe true
      }

      "should return true when connector is disabled" in new Setup() {
        configSetUpWith(enabled = false)
        val result = await(underTest.sendClientSecretRemovedEvent(clientSecretRemovedEvent)(hc))

        result shouldBe true
      }

      "should return false when httpclient receives internal server error status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendRedirectUrisUpdatedEvent(redirectUrisUpdatedEvent)(hc))

        result shouldBe false
      }
    }

    "RedirectUrisUpdatedEvent" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturnCreated
        val result = await(underTest.sendRedirectUrisUpdatedEvent(redirectUrisUpdatedEvent)(hc))

        result shouldBe true
      }

      "should return true when connector is disabled" in new Setup() {
        configSetUpWith(enabled = false)
        val result = await(underTest.sendRedirectUrisUpdatedEvent(redirectUrisUpdatedEvent)(hc))

        result shouldBe true
      }

      "should return false when httpclient receives internal server error status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendClientSecretRemovedEvent(clientSecretRemovedEvent)(hc))

        result shouldBe false
      }
    }

    "ApiSubscribedEvent" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturnCreated
        val result = await(underTest.sendApiSubscribedEvent(apiSubscribedEvent)(hc))

        result shouldBe true
      }

      "should return true when connector is disabled" in new Setup() {
        configSetUpWith(enabled = false)
        val result = await(underTest.sendApiSubscribedEvent(apiSubscribedEvent)(hc))

        result shouldBe true
      }

      "should return false when httpclient receives internal server error status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendApiSubscribedEvent(apiSubscribedEvent)(hc))

        result shouldBe false
      }
    }

    "ApiUnsubscribedEvent" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturnCreated
        val result = await(underTest.sendApiUnsubscribedEvent(apiUnSubscribedEvent)(hc))

        result shouldBe true
      }

      "should return true when connector is disabled" in new Setup() {
        configSetUpWith(enabled = false)
        val result = await(underTest.sendApiUnsubscribedEvent(apiUnSubscribedEvent)(hc))

        result shouldBe true
      }

      "should return false when httpclient receives internal server error status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillFailWith(INTERNAL_SERVER_ERROR)
        val result = await(underTest.sendApiUnsubscribedEvent(apiUnSubscribedEvent)(hc))

        result shouldBe false
      }
    }
  }

}
