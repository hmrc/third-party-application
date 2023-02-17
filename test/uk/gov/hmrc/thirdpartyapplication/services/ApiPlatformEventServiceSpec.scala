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

import org.mockito.captor.ArgCaptor
import org.scalatest.BeforeAndAfterEach
import org.scalatest.prop.TableDrivenPropertyChecks

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.connector.ApiPlatformEventsConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.{LOGGED_IN_USER_EMAIL_HEADER, LOGGED_IN_USER_NAME_HEADER}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.CollaboratorTestData
import uk.gov.hmrc.apiplatform.modules.common.domain.models.OldStyleActors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.domain.models.OldStyleActor

class ApiPlatformEventServiceSpec extends AsyncHmrcSpec with BeforeAndAfterEach with TableDrivenPropertyChecks with CollaboratorTestData {

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

  val adminEmail                            = "admin@admin.com"
  val teamMemberEmail                       = "bob@bob.com".toLaxEmail
  val teamMemberRole                        = "ADMIN"
  val context: ApiContext                           = "api/path/path2".asContext
  val version: ApiVersion                           = "2.0".asVersion
  val appDataWithCollaboratorAdded: ApplicationData = applicationData.copy(collaborators = Set(adminEmail.admin()))

  override def beforeEach(): Unit = {
    reset(mockConnector)
  }

  trait Setup {
    val objInTest: ApiPlatformEventService = new ApiPlatformEventService(mockConnector, FixedClock.clock )

  }

  val clientSecretId: String = UUID.randomUUID().toString

  val oldRedirectUris = "123/,456/,789/"

  val newRedirectUris = "123/,456/,789/,101112/"

  "ApiPlatformEventService" when {

    "TeamMemberAdded" should {

      "send event payload with actor type as COLLABORATOR when user sending the event is a collaborator" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)
        teamMemberAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = true, expectedResult = true, added = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {
        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAddedRemoved(objInTest, OldStyleActors.GatekeeperUser(userEmail), connectorResult = true, expectedResult = true, added = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = false, expectedResult = false, added = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberAddedRemoved(objInTest, OldStyleActors.GatekeeperUser("admin@gatekeeper"), connectorResult = true, expectedResult = true, added = true)
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

        teamMemberAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = true, expectedResult = true, added = false)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAddedRemoved(objInTest, OldStyleActors.GatekeeperUser(userEmail), connectorResult = true, expectedResult = true, added = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = false, expectedResult = false, added = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberAddedRemoved(objInTest, OldStyleActors.GatekeeperUser("admin@gatekeeper"), connectorResult = true, expectedResult = true, added = true)

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

        clientSecretAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = true, expectedResult = true, added = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        clientSecretAddedRemoved(objInTest, OldStyleActors.GatekeeperUser(userEmail), connectorResult = true, expectedResult = true, added = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        clientSecretAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = false, expectedResult = false, added = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        clientSecretAddedRemoved(objInTest, OldStyleActors.GatekeeperUser("admin@gatekeeper"), connectorResult = true, expectedResult = true, added = true)

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

        teamMemberAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = true, expectedResult = true, added = false)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        teamMemberAddedRemoved(objInTest,OldStyleActors.GatekeeperUser(userEmail), connectorResult = true, expectedResult = true, added = false)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        teamMemberAddedRemoved(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = false, expectedResult = false, added = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        teamMemberAddedRemoved(objInTest, OldStyleActors.GatekeeperUser("admin@gatekeeper"), connectorResult = true, expectedResult = true, added = false)

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

        redirectUrisUpdated(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = true, expectedResult = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        redirectUrisUpdated(objInTest,OldStyleActors.GatekeeperUser(userEmail), connectorResult = true, expectedResult = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        redirectUrisUpdated(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = false, expectedResult = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        redirectUrisUpdated(objInTest, OldStyleActors.GatekeeperUser("admin@gatekeeper"), connectorResult = true, expectedResult = true)

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

        apiSubscribedUnsubscribed(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = true, expectedResult = true, subscribed = true)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        apiSubscribedUnsubscribed(objInTest,OldStyleActors.GatekeeperUser(userEmail), connectorResult = true, expectedResult = true, subscribed = true)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        apiSubscribedUnsubscribed(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = false, expectedResult = false, subscribed = true)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        apiSubscribedUnsubscribed(objInTest, OldStyleActors.GatekeeperUser("admin@gatekeeper"), connectorResult = true, expectedResult = true, subscribed = true)

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

        apiSubscribedUnsubscribed(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = true, expectedResult = true, subscribed = false)
      }

      "send event payload with actor type as GATEKEEPER when user sending the event isn't a collaborator" in new Setup() {

        val userEmail: String             = "NonCollaboratorEmail"
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> userEmail)
        apiSubscribedUnsubscribed(objInTest,OldStyleActors.GatekeeperUser(userEmail), connectorResult = true, expectedResult = true, subscribed = false)
      }

      "send event and return false result from connector" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> adminEmail)

        apiSubscribedUnsubscribed(objInTest, OldStyleActors.Collaborator(adminEmail), connectorResult = false, expectedResult = false, subscribed = false)
      }

      "set actor to gatekeeper with default email when the logged in user header is not set" in new Setup() {
        implicit val newHc: HeaderCarrier = HeaderCarrier()
        apiSubscribedUnsubscribed(objInTest, OldStyleActors.GatekeeperUser("admin@gatekeeper"), connectorResult = true, expectedResult = true, subscribed = false)
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
        expectedActor: OldStyleActor,
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
        validateEvent(argumentCaptor.value, expectedActor)
      } else {
        val argumentCaptor = ArgCaptor[ClientSecretRemovedEvent]
        verify(mockConnector).sendClientSecretRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, expectedActor)
      }
    }

    def teamMemberAddedRemoved(
        objInTest: ApiPlatformEventService,
        expectedActor: OldStyleActor,
        connectorResult: Boolean,
        expectedResult: Boolean,
        added: Boolean
      )(implicit hc: HeaderCarrier
      ) = {
      if (added) when(mockConnector.sendTeamMemberAddedEvent(any[TeamMemberAddedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))
      else when(mockConnector.sendTeamMemberRemovedEvent(any[TeamMemberRemovedEvent])(any[HeaderCarrier])).thenReturn(Future.successful(connectorResult))

      val f: (ApplicationData, Map[String, String]) => Future[Boolean] = (appData: ApplicationData, data: Map[String, String]) => {
        val teamMemberEmail = data.getOrElse("teamMemberEmail", "").toLaxEmail
        val teamMemberRole  = data.getOrElse("teamMemberRole", "")

        if (added) objInTest.sendTeamMemberAddedEvent(appData, teamMemberEmail, teamMemberRole)
        else objInTest.sendTeamMemberRemovedEvent(appData, teamMemberEmail, teamMemberRole)
      }

      testService(f, expectedResult)
      if (added) {
        val argumentCaptor = ArgCaptor[TeamMemberAddedEvent]
        verify(mockConnector).sendTeamMemberAddedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, expectedActor)
      } else {
        val argumentCaptor = ArgCaptor[TeamMemberRemovedEvent]
        verify(mockConnector).sendTeamMemberRemovedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, expectedActor)
      }
    }

    def apiSubscribedUnsubscribed(
        objInTest: ApiPlatformEventService,
        expectedActor: OldStyleActor,
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
        validateEvent(argumentCaptor.value, expectedActor)
      } else {
        val argumentCaptor = ArgCaptor[ApiUnsubscribedEvent]
        verify(mockConnector).sendApiUnsubscribedEvent(argumentCaptor.capture)(any[HeaderCarrier])
        validateEvent(argumentCaptor.value, expectedActor)
      }
    }

    def redirectUrisUpdated(
        objInTest: ApiPlatformEventService,
        expectedActor: OldStyleActor,
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

      validateEvent(argumentCaptor.value, expectedActor)
    }

    def testService(f: (ApplicationData, Map[String, String]) => Future[Boolean], expectedResult: Boolean) = {
      val data = Map(
        "teamMemberEmail" -> teamMemberEmail.text,
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

    def validateEvent(applicationEvent: AbstractApplicationEvent, expectedActor: OldStyleActor) = applicationEvent match {
      case teamMemberAddedEvent: TeamMemberAddedEvent         =>
        val actor = teamMemberAddedEvent.actor
        actor shouldBe expectedActor
        teamMemberAddedEvent.teamMemberEmail shouldBe teamMemberEmail
        teamMemberAddedEvent.teamMemberRole shouldBe teamMemberRole
      case teamMemberRemovedEvent: TeamMemberRemovedEvent     =>
        val actor = teamMemberRemovedEvent.actor
        actor shouldBe expectedActor
        teamMemberRemovedEvent.teamMemberEmail shouldBe teamMemberEmail
        teamMemberRemovedEvent.teamMemberRole shouldBe teamMemberRole
      case clientSecretAddedEvent: ClientSecretAddedEvent     =>
        val actor = clientSecretAddedEvent.actor
        actor shouldBe expectedActor
        clientSecretAddedEvent.clientSecretId shouldBe clientSecretId
      case clientSecretRemovedEvent: ClientSecretRemovedEvent =>
        val actor = clientSecretRemovedEvent.actor
        actor shouldBe expectedActor
        clientSecretRemovedEvent.clientSecretId shouldBe clientSecretId
      case redirectUrisUpdatedEvent: RedirectUrisUpdatedEvent =>
        val actor = redirectUrisUpdatedEvent.actor
        actor shouldBe expectedActor
        redirectUrisUpdatedEvent.oldRedirectUris shouldBe oldRedirectUris
        redirectUrisUpdatedEvent.newRedirectUris shouldBe newRedirectUris
      case apiSubscribedEvent: ApiSubscribedEvent             =>
        val actor = apiSubscribedEvent.actor
        actor shouldBe expectedActor
        apiSubscribedEvent.context shouldBe context.value
        apiSubscribedEvent.version shouldBe version.value
      case apiUnSubscribedEvent: ApiUnsubscribedEvent         =>
        val actor = apiUnSubscribedEvent.actor
        actor shouldBe expectedActor
        apiUnSubscribedEvent.context shouldBe context.value
        apiUnSubscribedEvent.version shouldBe version.value

    }
  }

}
