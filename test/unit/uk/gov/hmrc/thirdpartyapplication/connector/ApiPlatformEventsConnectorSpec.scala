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
import uk.gov.hmrc.thirdpartyapplication.models.{Actor, ActorType, HasSucceeded, TeamMemberAddedEvent}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApiPlatformEventsConnectorSpec extends ConnectorSpec with ScalaFutures {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val baseUrl = s"http://example.com"

  val event: TeamMemberAddedEvent = TeamMemberAddedEvent(applicationId = "jkkh",
    actor = Actor(id = "bob@bob.com", ActorType.COLLABORATOR),
    teamMemberEmail = "teamMember@teamMember.com",
    teamMemberRole = "ADMIN")

  trait Setup {
    val mockHttpClient: HttpClient = mock[HttpClient]
    val config: ApiPlatformEventsConfig = ApiPlatformEventsConfig(baseUrl, enabled = true)

    val underTest = new ApiPlatformEventsConnector(mockHttpClient, config)

    def apiApplicationEventsWillReturn(result: Future[HttpResponse]): ScalaOngoingStubbing[Future[HttpResponse]] = {
      when(mockHttpClient.POST[TeamMemberAddedEvent, HttpResponse](*, *, *)(*, *, *, *)).thenReturn(result)
    }
  }

  "ApiPlatformEventsConnector" should {

    "should return true when httpclient receives CREATED status" in new Setup() {
      apiApplicationEventsWillReturn(Future(HttpResponse(CREATED)))
      val result = await(underTest.sendTeamMemberAddedEvent(event)(hc))

      result shouldBe true
    }

    "should return false when httpclient receives internal server error status" in new Setup() {
      apiApplicationEventsWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR)))
      val result = await(underTest.sendTeamMemberAddedEvent(event)(hc))

      result shouldBe true
    }
  }

}
