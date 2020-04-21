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
import org.scalatest.prop.TableDrivenPropertyChecks
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

class ApiPlatformEventServiceSpec extends AsyncHmrcSpec with BeforeAndAfterEach with TableDrivenPropertyChecks {

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

  val clientSecretId: String = UUID.randomUUID().toString

  val oldRedirectUris = "123/,456/,789/"

  val newRedirectUris = "123/,456/,789/,101112/"

  "ApiPlatformEventService" when {

    "TeamMemberAdded" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)
        teamMemberAdded(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {
        val userEmail: String = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAdded(objInTest,  userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)
      }


      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAdded(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberAdded(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)
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

        teamMemberRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberRemoved(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)
      }


      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberRemoved(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)

      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

        val result: Boolean = await(objInTest.sendTeamMemberRemovedEvent(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))

        result shouldBe false

        verifyZeroInteractions(mockConnector)
      }
    }

    "ClientSecretAddedEvent" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        clientSecretAdded(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        clientSecretAdded(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)
      }


      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        clientSecretAdded(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        clientSecretAdded(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)

      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

        val result: Boolean = await(objInTest.sendClientSecretAddedEvent(appDataWithCollaboratorAdded, clientSecretId))

        result shouldBe false

        verifyZeroInteractions(mockConnector)
      }
    }


    "ClientSecretRemovedEvent" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        clientSecretRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        clientSecretRemoved(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)
      }


      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        clientSecretRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        clientSecretRemoved(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)

      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

        val result: Boolean = await(objInTest.sendClientSecretRemovedEvent(appDataWithCollaboratorAdded, clientSecretId))

        result shouldBe false

        verifyZeroInteractions(mockConnector)
      }
    }

    "RedirectUrisUpdatedEvent" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        redirectUrisUpdated(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        redirectUrisUpdated(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)
      }


      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        redirectUrisUpdated(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        redirectUrisUpdated(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true)

      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

        val result: Boolean = await(objInTest.sendRedirectUrisUpdatedEvent(appDataWithCollaboratorAdded, oldRedirectUris, newRedirectUris))

        result shouldBe false

        verifyZeroInteractions(mockConnector)
      }
    }

    def clientSecretAdded(objInTest: ApiPlatformEventService,
                        loggedInUserEmail: String,
                        expectedActorType: ActorType,
                        connectorResult: Boolean,
                        expectedResult: Boolean)(implicit hc:HeaderCarrier) = {
      when(mockConnector.sentClientSecretAddedEvent(any[ClientSecretAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val clientSecretId = data.getOrElse("clientSecretId", "")
        objInTest.sendClientSecretAddedEvent(appData, clientSecretId)
      }

      testService(f, expectedResult)

     val argumentCaptor = ArgCaptor[ClientSecretAddedEvent]
     verify(mockConnector).sentClientSecretAddedEvent(argumentCaptor.capture)(any[HeaderCarrier])

     validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
    }

    def clientSecretRemoved(objInTest: ApiPlatformEventService,
                          loggedInUserEmail: String,
                          expectedActorType: ActorType,
                          connectorResult: Boolean,
                          expectedResult: Boolean)(implicit hc:HeaderCarrier) = {
      when(mockConnector.sentClientSecretRemovedEvent(any[ClientSecretRemovedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val clientSecretId = data.getOrElse("clientSecretId", "")
        objInTest.sendClientSecretRemovedEvent(appData, clientSecretId)
      }

      testService(f, expectedResult)

      val argumentCaptor = ArgCaptor[ClientSecretRemovedEvent]
      verify(mockConnector).sentClientSecretRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
    }

    def teamMemberAdded(objInTest: ApiPlatformEventService,
                    loggedInUserEmail: String,
                    expectedActorType: ActorType,
                    connectorResult: Boolean,
                    expectedResult: Boolean)(implicit hc:HeaderCarrier) = {
      when(mockConnector.sendTeamMemberAddedEvent(any[TeamMemberAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val teamMemberEmail = data.getOrElse("teamMemberEmail", "")
        val teamMemberRole = data.getOrElse("teamMemberRole", "")
        objInTest.sendTeamMemberAddedEvent(appData, teamMemberEmail, teamMemberRole)
      }

      testService(f, expectedResult)

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

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val teamMemberEmail = data.getOrElse("teamMemberEmail", "")
        val teamMemberRole = data.getOrElse("teamMemberRole", "")
        objInTest.sendTeamMemberRemovedEvent(appData, teamMemberEmail, teamMemberRole)
      }
      testService(f, expectedResult)
      val argumentCaptor = ArgCaptor[TeamMemberRemovedEvent]
      verify(mockConnector).sendTeamMemberRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
    }

    def redirectUrisUpdated(objInTest: ApiPlatformEventService,
                          loggedInUserEmail: String,
                          expectedActorType: ActorType,
                          connectorResult: Boolean,
                          expectedResult: Boolean)(implicit hc:HeaderCarrier) = {
      when(mockConnector.sentRedirectUrisUpdatedEvent(any[RedirectUrisUpdatedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val newRedirectUris = data.getOrElse("newRedirectUris", "")
        val oldRedirectUris = data.getOrElse("oldRedirectUris", "")
        objInTest.sendRedirectUrisUpdatedEvent(appData, oldRedirectUris, newRedirectUris)
      }
      testService(f, expectedResult)
      val argumentCaptor = ArgCaptor[RedirectUrisUpdatedEvent]
      verify(mockConnector).sentRedirectUrisUpdatedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
    }


    def testService(f: (ApplicationData, Map[String, String]) => Future[Boolean],
                    expectedResult: Boolean)(implicit hc: HeaderCarrier) = {
      val data = Map("teamMemberEmail" -> teamMemberEmail,
        "teamMemberRole" -> teamMemberRole,
        "clientSecretId" -> clientSecretId,
        "oldRedirectUris" -> oldRedirectUris,
        "newRedirectUris" -> newRedirectUris)

      val result: Boolean = await(f.apply(appDataWithCollaboratorAdded, data))
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
      case clientSecretAddedEvent: ClientSecretAddedEvent =>
        val actor = clientSecretAddedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        clientSecretAddedEvent.eventType shouldBe EventType.CLIENT_SECRET_ADDED
        clientSecretAddedEvent.clientSecretId shouldBe clientSecretId
      case clientSecretRemovedEvent: ClientSecretRemovedEvent =>
        val actor = clientSecretRemovedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        clientSecretRemovedEvent.eventType shouldBe EventType.CLIENT_SECRET_REMOVED
        clientSecretRemovedEvent.clientSecretId shouldBe clientSecretId
      case redirectUrisUpdatedEvent: RedirectUrisUpdatedEvent =>
        val actor = redirectUrisUpdatedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        redirectUrisUpdatedEvent.eventType shouldBe EventType.REDIRECT_URIS_UPDATED
        redirectUrisUpdatedEvent.oldRedirectUris shouldBe oldRedirectUris
        redirectUrisUpdatedEvent.newRedirectUris shouldBe newRedirectUris

    }
  }

}
