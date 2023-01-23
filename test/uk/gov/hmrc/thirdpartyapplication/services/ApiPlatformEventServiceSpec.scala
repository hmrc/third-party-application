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

package uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import cats.data.NonEmptyList
import org.mockito.captor.ArgCaptor
import org.scalatest.BeforeAndAfterEach
import org.scalatest.prop.TableDrivenPropertyChecks

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.connector.ApiPlatformEventsConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType.ActorType
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ClientSecretAdded, ClientSecretAddedObfuscated, CollaboratorActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.{LOGGED_IN_USER_EMAIL_HEADER, LOGGED_IN_USER_NAME_HEADER}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}

class ApiPlatformEventServiceSpec extends AsyncHmrcSpec with BeforeAndAfterEach with TableDrivenPropertyChecks {

  val mockConnector: ApiPlatformEventsConnector = mock[ApiPlatformEventsConnector]

  val applicationState: ApplicationState = ApplicationState(name = State.TESTING, requestedByEmailAddress = None, verificationCode = None)

  val applicationData: ApplicationData = ApplicationData(
    id = ApplicationId.random,
    name = "name",
    normalisedName = "normalisedName",
    collaborators = Set.empty,
    description = None,
    wso2ApplicationName = "wso2Name",
    tokens = ApplicationTokens(Token(ClientId("clientId"), "accessToken", List.empty)),
    state = applicationState,
    createdOn = FixedClock.now,
    lastAccess = None,
    rateLimitTier = None,
    environment = "",
    checkInformation = None
  )

  val adminEmail: String                            = "admin@admin.com"
  val teamMemberEmail: String                       = "bob@bob.com"
  val teamMemberRole: String                        = "ADMIN"
  val context: ApiContext                           = "api/path/path2".asContext
  val version: ApiVersion                           = "2.0".asVersion
  val appDataWithCollaboratorAdded: ApplicationData = applicationData.copy(collaborators = Set(Collaborator(adminEmail, Role.ADMINISTRATOR, UserId.random)))

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

    "applyEvents" should {
      val secretValue            = "secretValue"
      val clientSecretAddedEvent = ClientSecretAdded(
        id = UpdateApplicationEvent.Id.random,
        applicationId = applicationData.id,
        eventDateTime = FixedClock.now,
        actor = CollaboratorActor(adminEmail),
        secretValue = secretValue,
        clientSecret = ClientSecret("name", FixedClock.now, None, UUID.randomUUID().toString, "eulaVterces")
      )
      "obfuscate ClientSecret Event when applied" in new Setup() {
        val obfuscatedEvent = ClientSecretAddedObfuscated.fromClientSecretAdded(clientSecretAddedEvent)
        when(mockConnector.sendApplicationEvent(eqTo(obfuscatedEvent))(*))
          .thenReturn(Future.successful(true))

        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)
        val result                        = await(objInTest.applyEvents(NonEmptyList.of(clientSecretAddedEvent)))
        result shouldBe true
      }
    }

    "TeamMemberAdded" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)
        teamMemberAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true, added = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {
        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAddedRemoved(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false, added = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberAddedRemoved(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = true)
      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")
        val result: Boolean               = await(objInTest.sendTeamMemberAddedEvent(appDataWithCollaboratorAdded, teamMemberEmail, teamMemberRole))

        result shouldBe false
        verifyZeroInteractions(mockConnector)
      }
    }
    "TeamMemberRemoved" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true, added = false)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAddedRemoved(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false, added = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberAddedRemoved(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = true)

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

        clientSecretAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true, added = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        clientSecretAddedRemoved(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        clientSecretAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false, added = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        clientSecretAddedRemoved(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = true)

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

        teamMemberAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true, added = false)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAddedRemoved(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = false)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAddedRemoved(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false, added = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberAddedRemoved(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, added = false)

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

        val userEmail: String             = "NonCollaboratorEmail"
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

    "ApiSubscribedEvent" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        apiSubscribedUnsubscribed(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true, subscribed = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        apiSubscribedUnsubscribed(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, subscribed = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        apiSubscribedUnsubscribed(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false, subscribed = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        apiSubscribedUnsubscribed(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, subscribed = true)

      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

        val result: Boolean = await(objInTest.sendApiSubscribedEvent(appDataWithCollaboratorAdded, context, version))

        result shouldBe false

        verifyZeroInteractions(mockConnector)
      }
    }

    "ApiUnsubscribedEvent" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        apiSubscribedUnsubscribed(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = true, expectedResult = true, subscribed = false)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        apiSubscribedUnsubscribed(objInTest, userEmail, ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, subscribed = false)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        apiSubscribedUnsubscribed(objInTest, adminEmail, ActorType.COLLABORATOR, connectorResult = false, expectedResult = false, subscribed = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        apiSubscribedUnsubscribed(objInTest, "admin@gatekeeper", ActorType.GATEKEEPER, connectorResult = true, expectedResult = true, subscribed = false)

      }

      "return false when username header is set but not user email header" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_NAME_HEADER -> "someuserName")

        val result: Boolean = await(objInTest.sendApiUnsubscribedEvent(appDataWithCollaboratorAdded, context, version))

        result shouldBe false

        verifyZeroInteractions(mockConnector)
      }
    }

    def clientSecretAddedRemoved(
        objInTest: ApiPlatformEventService,
        loggedInUserEmail: String,
        expectedActorType: ActorType,
        connectorResult: Boolean,
        expectedResult: Boolean,
        added: Boolean
      )(implicit hc: HeaderCarrier
      ) = {
      if (added) when(mockConnector.sendClientSecretAddedEvent(any[ClientSecretAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))
      else when(mockConnector.sendClientSecretRemovedEvent(any[ClientSecretRemovedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val clientSecretId = data.getOrElse("clientSecretId", "")
        if (added) objInTest.sendClientSecretAddedEvent(appData, clientSecretId)
        else objInTest.sendClientSecretRemovedEvent(appData, clientSecretId)
      }

      testService(f, expectedResult)
      if (added) {
        val argumentCaptor = ArgCaptor[ClientSecretAddedEvent]
        verify(mockConnector).sendClientSecretAddedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
      } else {
        val argumentCaptor = ArgCaptor[ClientSecretRemovedEvent]
        verify(mockConnector).sendClientSecretRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
      }
    }

    def teamMemberAddedRemoved(
        objInTest: ApiPlatformEventService,
        loggedInUserEmail: String,
        expectedActorType: ActorType,
        connectorResult: Boolean,
        expectedResult: Boolean,
        added: Boolean
      )(implicit hc: HeaderCarrier
      ) = {
      if (added) when(mockConnector.sendTeamMemberAddedEvent(any[TeamMemberAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))
      else when(mockConnector.sendTeamMemberRemovedEvent(any[TeamMemberRemovedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val teamMemberEmail = data.getOrElse("teamMemberEmail", "")
        val teamMemberRole  = data.getOrElse("teamMemberRole", "")
        if (added) objInTest.sendTeamMemberAddedEvent(appData, teamMemberEmail, teamMemberRole)
        else objInTest.sendTeamMemberRemovedEvent(appData, teamMemberEmail, teamMemberRole)
      }

      testService(f, expectedResult)
      if (added) {
        val argumentCaptor = ArgCaptor[TeamMemberAddedEvent]
        verify(mockConnector).sendTeamMemberAddedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
      } else {
        val argumentCaptor = ArgCaptor[TeamMemberRemovedEvent]
        verify(mockConnector).sendTeamMemberRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
      }
    }

    def apiSubscribedUnsubscribed(
        objInTest: ApiPlatformEventService,
        loggedInUserEmail: String,
        expectedActorType: ActorType,
        connectorResult: Boolean,
        expectedResult: Boolean,
        subscribed: Boolean
      )(implicit hc: HeaderCarrier
      ) = {
      if (subscribed) when(mockConnector.sendApiSubscribedEvent(any[ApiSubscribedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))
      else when(mockConnector.sendApiUnsubscribedEvent(any[ApiUnsubscribedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val context = data.get("context").map(ApiContext(_)).get
        val version = data.get("version").map(ApiVersion(_)).get
        if (subscribed) objInTest.sendApiSubscribedEvent(appData, context, version)
        else objInTest.sendApiUnsubscribedEvent(appData, context, version)
      }

      testService(f, expectedResult)
      if (subscribed) {
        val argumentCaptor = ArgCaptor[ApiSubscribedEvent]
        verify(mockConnector).sendApiSubscribedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
      } else {
        val argumentCaptor = ArgCaptor[ApiUnsubscribedEvent]
        verify(mockConnector).sendApiUnsubscribedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
      }
    }

    def redirectUrisUpdated(
        objInTest: ApiPlatformEventService,
        loggedInUserEmail: String,
        expectedActorType: ActorType,
        connectorResult: Boolean,
        expectedResult: Boolean
      )(implicit hc: HeaderCarrier
      ) = {
      when(mockConnector.sendRedirectUrisUpdatedEvent(any[RedirectUrisUpdatedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val newRedirectUris = data.getOrElse("newRedirectUris", "")
        val oldRedirectUris = data.getOrElse("oldRedirectUris", "")
        objInTest.sendRedirectUrisUpdatedEvent(appData, oldRedirectUris, newRedirectUris)
      }
      testService(f, expectedResult)
      val argumentCaptor                                               = ArgCaptor[RedirectUrisUpdatedEvent]
      verify(mockConnector).sendRedirectUrisUpdatedEvent(argumentCaptor.capture)(any[HeaderCarrier])

      validateEvent(argumentCaptor.value, loggedInUserEmail, expectedActorType)
    }

    def testService(f: (ApplicationData, Map[String, String]) => Future[Boolean], expectedResult: Boolean) = {
      val data = Map(
        "teamMemberEmail" -> teamMemberEmail,
        "teamMemberRole"  -> teamMemberRole,
        "clientSecretId"  -> clientSecretId,
        "oldRedirectUris" -> oldRedirectUris,
        "newRedirectUris" -> newRedirectUris,
        "context"         -> context.value,
        "version"         -> version.value
      )

      val result: Boolean = await(f.apply(appDataWithCollaboratorAdded, data))
      result shouldBe expectedResult

    }

    def validateEvent(applicationEvent: ApplicationEvent, loggedInUserEmail: String, expectedActorType: ActorType) = applicationEvent match {
      case teamMemberAddedEvent: TeamMemberAddedEvent         =>
        val actor = teamMemberAddedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        teamMemberAddedEvent.teamMemberEmail shouldBe teamMemberEmail
        teamMemberAddedEvent.teamMemberRole shouldBe teamMemberRole
      case teamMemberRemovedEvent: TeamMemberRemovedEvent     =>
        val actor = teamMemberRemovedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        teamMemberRemovedEvent.teamMemberEmail shouldBe teamMemberEmail
        teamMemberRemovedEvent.teamMemberRole shouldBe teamMemberRole
      case clientSecretAddedEvent: ClientSecretAddedEvent     =>
        val actor = clientSecretAddedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        clientSecretAddedEvent.clientSecretId shouldBe clientSecretId
      case clientSecretRemovedEvent: ClientSecretRemovedEvent =>
        val actor = clientSecretRemovedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        clientSecretRemovedEvent.clientSecretId shouldBe clientSecretId
      case redirectUrisUpdatedEvent: RedirectUrisUpdatedEvent =>
        val actor = redirectUrisUpdatedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        redirectUrisUpdatedEvent.oldRedirectUris shouldBe oldRedirectUris
        redirectUrisUpdatedEvent.newRedirectUris shouldBe newRedirectUris
      case apiSubscribedEvent: ApiSubscribedEvent             =>
        val actor = apiSubscribedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        apiSubscribedEvent.context shouldBe context.value
        apiSubscribedEvent.version shouldBe version.value
      case apiUnSubscribedEvent: ApiUnsubscribedEvent         =>
        val actor = apiUnSubscribedEvent.actor
        actor.id shouldBe loggedInUserEmail
        actor.actorType shouldBe expectedActorType
        apiUnSubscribedEvent.context shouldBe context.value
        apiUnSubscribedEvent.version shouldBe version.value

    }
  }

}
