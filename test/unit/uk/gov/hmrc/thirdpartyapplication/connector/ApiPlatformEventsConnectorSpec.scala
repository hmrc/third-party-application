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

package unit.uk.gov.hmrc.thirdpartyapplication.connector

import org.mockito.stubbing.ScalaOngoingStubbing
import org.scalatest.concurrent.ScalaFutures
import play.api.http.Status.{CREATED, INTERNAL_SERVER_ERROR}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiPlatformEventsConfig, ApiPlatformEventsConnector}
import uk.gov.hmrc.thirdpartyapplication.models._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApiPlatformEventsConnectorSpec extends ConnectorSpec with ScalaFutures {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val baseUrl = s"http://example.com"

  val teamMemberAddedEvent: TeamMemberAddedEvent = TeamMemberAddedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    teamMemberEmail = "teamMember@teamMember.com",
    teamMemberRole = "ADMIN")

  val teamMemberRemovedEvent: TeamMemberRemovedEvent = TeamMemberRemovedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    teamMemberEmail = "teamMember@teamMember.com",
    teamMemberRole = "ADMIN")

  val clientSecretAddedEvent: ClientSecretAddedEvent = ClientSecretAddedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    clientSecretId = "1234")

  val clientSecretRemovedEvent: ClientSecretRemovedEvent = ClientSecretRemovedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    clientSecretId = "1234")

  val redirectUrisUpdatedEvent: RedirectUrisUpdatedEvent = RedirectUrisUpdatedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    oldRedirectUris = "originalUris", newRedirectUris = "newRedirectUris")

  val apiSubscribedEvent: ApiSubscribedEvent = ApiSubscribedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR), context = "context", version = "2.0")

  val apiUnSubscribedEvent: ApiUnsubscribedEvent = ApiUnsubscribedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR), context = "context", version = "2.0")

  trait Setup {
    val mockHttpClient: HttpClient = mock[HttpClient]
    val mockConfig: ApiPlatformEventsConfig = mock[ApiPlatformEventsConfig](withSettings.lenient())

    val underTest = new ApiPlatformEventsConnector(mockHttpClient, mockConfig)

    def apiApplicationEventsWillReturn(result: Future[HttpResponse]): ScalaOngoingStubbing[Future[HttpResponse]] = {
      when(mockHttpClient.POST[TeamMemberAddedEvent, HttpResponse](*, *, *)(*, *, *, *)).thenReturn(result)
    }
    def configSetUpWith(enabled: Boolean): ScalaOngoingStubbing[String] ={
      when(mockConfig.enabled).thenReturn(enabled)
      when(mockConfig.baseUrl).thenReturn(baseUrl)
    }
  }

  "ApiPlatformEventsConnector" when {

    "TeamMemberAddedEvents" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
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
        apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
        val result = await(underTest.sendTeamMemberAddedEvent(teamMemberAddedEvent)(hc))

        result shouldBe true
      }
    }

    "TeamMemberRemovedEvents" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
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
        apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
        val result = await(underTest.sendTeamMemberRemovedEvent(teamMemberRemovedEvent)(hc))

        result shouldBe true
      }
    }


    "ClientSecretAdded" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
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
        apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
        val result = await(underTest.sendClientSecretAddedEvent(clientSecretAddedEvent)(hc))

        result shouldBe true
      }
    }

    "ClientSecretRemoved" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
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
        apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
        val result = await(underTest.sendRedirectUrisUpdatedEvent(redirectUrisUpdatedEvent)(hc))

        result shouldBe true
      }
    }

    "RedirectUrisUpdatedEvent" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
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
        apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
        val result = await(underTest.sendClientSecretRemovedEvent(clientSecretRemovedEvent)(hc))

        result shouldBe true
      }
    }

    "ApiSubscribedEvent" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
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
        apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
        val result = await(underTest.sendApiSubscribedEvent(apiSubscribedEvent)(hc))

        result shouldBe true
      }
    }

    "ApiUnsubscribedEvent" should {
      "should return true when httpclient receives CREATED status" in new Setup() {
        configSetUpWith(enabled = true)
        apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
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
        apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
        val result = await(underTest.sendApiUnsubscribedEvent(apiUnSubscribedEvent)(hc))

        result shouldBe true
      }
    }
  }

}
