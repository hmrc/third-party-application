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

  "ApiPlatformEventService" when {

    "TeamMemberAdded" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAdded(objInTest = objInTest,
          loggedInUserEmail = adminEmail,
          expectedActorType = ActorType.COLLABORATOR,
          connectorResult = true,
          expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAdded(objInTest = objInTest,
          loggedInUserEmail = userEmail,
          expectedActorType = ActorType.GATEKEEPER,
          connectorResult = true,
          expectedResult = true)
      }


      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAdded(objInTest = objInTest,
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
    }
    "TeamMemberRemoved" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberRemoved(objInTest = objInTest,
          loggedInUserEmail = adminEmail,
          expectedActorType = ActorType.COLLABORATOR,
          connectorResult = true,
          expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberRemoved(objInTest = objInTest,
          loggedInUserEmail = userEmail,
          expectedActorType = ActorType.GATEKEEPER,
          connectorResult = true,
          expectedResult = true)
      }


      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberRemoved(objInTest = objInTest,
          loggedInUserEmail = adminEmail,
          expectedActorType = ActorType.COLLABORATOR,
          connectorResult = false,
          expectedResult = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        when(mockConnector.sendTeamMemberRemovedEvent(any[TeamMemberRemovedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(true))

        val result: Boolean = await(objInTest.sendTeamMemberRemovedEvent(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))

        result shouldBe true

        val argumentCaptor = ArgCaptor[TeamMemberRemovedEvent]
        verify(mockConnector).sendTeamMemberRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, loggedInUserEmail = "admin@gatekeeper",  ActorType.GATEKEEPER)

      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

        val result: Boolean = await(objInTest.sendTeamMemberRemovedEvent(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))

        result shouldBe false

        verifyZeroInteractions(mockConnector)
      }
    }

    def teamMemberAdded(objInTest: ApiPlatformEventService,
                    loggedInUserEmail: String,
                    expectedActorType: ActorType,
                    connectorResult: Boolean,
                    expectedResult: Boolean)(implicit hc:HeaderCarrier) = {
      when(mockConnector.sendTeamMemberAddedEvent(any[TeamMemberAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, String, String) => Future[Boolean] = (appData: ApplicationData, email:String, role:String) => {
       objInTest.sendTeamMemberAddedEvent(appData, email, role)
      }

      testService(loggedInUserEmail, expectedActorType, f, connectorResult, expectedResult)

      val argumentCaptor = ArgCaptor[TeamMemberAddedEvent]
      verify(mockConnector).sendTeamMemberAddedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
    }

    def teamMemberRemoved(objInTest: ApiPlatformEventService,
                        loggedInUserEmail: String,
                        expectedActorType: ActorType,
                        connectorResult: Boolean,
                        expectedResult: Boolean)(implicit hc:HeaderCarrier) = {
      when(mockConnector.sendTeamMemberRemovedEvent(any[TeamMemberRemovedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, String, String) => Future[Boolean] = (appData: ApplicationData, email:String, role:String) => {
        objInTest.sendTeamMemberRemovedEvent(appData, email, role)
      }

      testService(loggedInUserEmail, expectedActorType, f, connectorResult, expectedResult)

      val argumentCaptor = ArgCaptor[TeamMemberRemovedEvent]
      verify(mockConnector).sendTeamMemberRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
    }


    def testService(loggedInUserEmail: String,
                    expectedActorType: ActorType,
                    f: (ApplicationData, String, String) => Future[Boolean],
                    connectorResult: Boolean,
                    expectedResult: Boolean)(implicit hc: HeaderCarrier) = {

      val result: Boolean = await(f.apply(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))
      result shouldBe expectedResult

    }

    def validateEvent(applicationEvent: ApplicationEvent, loggedInUserEmail: String, expectedActorType: ActorType) = applicationEvent match {
      case teamMemberAddedEvent: TeamMemberAddedEvent =>
        val actor = teamMemberAddedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        teamMemberAddedEvent.eventType shouldBe EventType.TEAM_MEMBER_ADDED
        teamMemberAddedEvent.teamMemberEmail shouldBe teamMemberEmail
        teamMemberAddedEvent.teamMemberRole shouldBe teamMemberRole
      case teamMemberRemovedEvent: TeamMemberRemovedEvent =>
        val actor = teamMemberRemovedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        teamMemberRemovedEvent.eventType shouldBe EventType.TEAM_MEMBER_REMOVED
        teamMemberRemovedEvent.teamMemberEmail shouldBe teamMemberEmail
        teamMemberRemovedEvent.teamMemberRole shouldBe teamMemberRole
    }
  }

}
