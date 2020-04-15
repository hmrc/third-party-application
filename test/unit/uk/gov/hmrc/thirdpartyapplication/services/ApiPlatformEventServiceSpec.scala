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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import org.joda.time.DateTime
import org.mockito.captor.ArgCaptor
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.ApiPlatformEventsConnector
import uk.gov.hmrc.thirdpartyapplication.models.ActorType.ActorType
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.ApiPlatformEventService
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.{LOGGED_IN_USER_EMAIL_HEADER, LOGGED_IN_USER_NAME_HEADER}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApiPlatformEventServiceSpec extends AsyncHmrcSpec with BeforeAndAfterEach {

  val mockConnector: ApiPlatformEventsConnector = mock[ApiPlatformEventsConnector]

  val applicationState: ApplicationState = ApplicationState(name = State.TESTING, requestedByEmailAddress = None, verificationCode = None)
  val applicationData: ApplicationData = ApplicationData(id = UUID.randomUUID(), name = "name", normalisedName = "normalisedName", collaborators = Set.empty,
    description = None, wso2ApplicationName = "wso2Name",
    tokens = ApplicationTokens(EnvironmentToken("clientId", "accessToken", List.empty)),
    state = applicationState,
    createdOn = DateTime.now(),
    lastAccess = None,
    rateLimitTier = None,
    environment = "",
    checkInformation = None)

  val adminEmail: String = "admin@admin.com"
  val teamMemberEmail: String = "bob@bob.com"
  val teamMemberRole: String = "ADMIN"
  val appDataWithCollaboratorAdded: ApplicationData = applicationData.copy(collaborators = Set(Collaborator(adminEmail, Role.ADMINISTRATOR)))

  override def beforeEach(): Unit = {
    reset(mockConnector)
  }


  trait Setup {
    val objInTest: ApiPlatformEventService = new ApiPlatformEventService(mockConnector)

  }

  "ApiPlatformEventService" should {

    "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
      testService(objInTest = objInTest,
        loggedInUserEmail = adminEmail,
        expectedActorType = ActorType.COLLABORATOR,
        connectorResult = true,
        expectedResult = true)
    }

    "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {
      val userEmail: String = "NonCollaboratorEmail"
      testService(objInTest = objInTest,
        loggedInUserEmail = userEmail,
        expectedActorType = ActorType.GATEKEEPER,
        connectorResult = true,
        expectedResult = true)
    }


    "send event and return false result from connector" in new Setup() {
      testService(objInTest = objInTest,
        loggedInUserEmail = adminEmail,
        expectedActorType = ActorType.COLLABORATOR,
        connectorResult = false,
        expectedResult = false)
    }

    "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
      implicit val newHc: HeaderCarrier = HeaderCarrier()
      when(mockConnector.sendTeamMemberAddedEvent(any[TeamMemberAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(true))

      val result: Boolean = await(objInTest.sendTeamMemberAddedEvent(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))

      result shouldBe true

      val argumentCaptor = ArgCaptor[TeamMemberAddedEvent]
      verify(mockConnector).sendTeamMemberAddedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      val capturedEvent = argumentCaptor.value
      val actor = capturedEvent.actor
      actor.id shouldBe "admin@gatekeeper"
      actor.actorType shouldBe ActorType.GATEKEEPER
      capturedEvent.eventType shouldBe EventType.TEAM_MEMBER_ADDED
      capturedEvent.teamMemberEmail shouldBe teamMemberEmail
      capturedEvent.teamMemberRole shouldBe teamMemberRole
    }

    "return false when username header is set but not user email header" in new Setup() {
      implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

      val result: Boolean = await(objInTest.sendTeamMemberAddedEvent(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))

      result shouldBe false

      verifyZeroInteractions(mockConnector)
    }

    def testService(objInTest: ApiPlatformEventService,
                    loggedInUserEmail: String,
                    expectedActorType: ActorType,
                    connectorResult: Boolean,
                    expectedResult: Boolean) = {
      when(mockConnector.sendTeamMemberAddedEvent(any[TeamMemberAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> loggedInUserEmail)

      val result: Boolean = await(objInTest.sendTeamMemberAddedEvent(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))

      result shouldBe expectedResult
      val argumentCaptor = ArgCaptor[TeamMemberAddedEvent]
      verify(mockConnector).sendTeamMemberAddedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      val capturedEvent = argumentCaptor.value
      val actor = capturedEvent.actor
      actor.id shouldBe loggedInUserEmail
      actor.actorType shouldBe expectedActorType
      capturedEvent.eventType shouldBe EventType.TEAM_MEMBER_ADDED
      capturedEvent.teamMemberEmail shouldBe teamMemberEmail
      capturedEvent.teamMemberRole shouldBe teamMemberRole
    }
  }

}
