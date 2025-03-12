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

package uk.gov.hmrc.thirdpartyapplication.component

import java.util.UUID
import scala.concurrent.Await.{ready, result}
import scala.util.Random

import org.scalatest.{EitherValues, Inside}
import sttp.client3._
import sttp.model.{Header, Method, StatusCode}

import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{Json, OWrites}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, ApplicationCommands, DispatchRequest}
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.controllers.ApplicationCommandController._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.util._

class DummyCredentialGenerator extends CredentialGenerator {
  override def generate() = "a" * 10
}

class ThirdPartyApplicationComponentISpec extends BaseFeatureSpec with EitherValues with CollaboratorTestData with Inside with FixedClock {

  val configOverrides = Map[String, Any](
    "microservice.services.api-subscription-fields.port"         -> 19650,
    "microservice.services.api-platform-events.port"             -> 16700,
    "microservice.services.api-gateway-stub.port"                -> 19607,
    "microservice.services.auth.port"                            -> 18500,
    "microservice.services.email.port"                           -> 18300,
    "microservice.services.third-party-delegated-authority.port" -> 19606,
    "microservice.services.totp.port"                            -> 19988,
    "mongodb.uri"                                                -> "mongodb://localhost:27017/third-party-application-test"
  )

  override def fakeApplication(): play.api.Application = {
    GuiceApplicationBuilder()
      .configure(configOverrides + ("metrics.jvm" -> false))
      .overrides(bind[CredentialGenerator].to[DummyCredentialGenerator])
      .disable(classOf[SchedulerModule])
      .build()
  }

  val applicationName1             = "My 1st Application"
  val applicationName2             = "My 2nd Application"
  val emailAddress                 = "user@example.com"
  val userId                       = UserId.random
  val adminUserId                  = UserId.random
  val testUserId                   = UserId.random
  val gatekeeperUserId             = "gate.keeper"
  val username                     = "a" * 10
  val password                     = "a" * 10
  val awsApiGatewayApplicationName = "a" * 10
  val testCookieLength             = 10
  val cookie                       = Random.alphanumeric.take(testCookieLength).mkString
  val serviceName                  = "service"
  val apiName                      = "apiName"
  val context                      = "myapi".asContext
  val version                      = "1.0".asVersion

  val standardAccess   = Access.Standard(
    redirectUris = List(LoginRedirectUri.unsafeApply("https://example.com/redirect")),
    termsAndConditionsUrl = Some("http://example.com/terms"),
    privacyPolicyUrl = Some("http://example.com/privacy"),
    overrides = Set.empty
  )
  val privilegedAccess = Access.Privileged(totpIds = None, scopes = Set("ogdScope"))

  lazy val subscriptionRepository = app.injector.instanceOf[SubscriptionRepository]
  lazy val applicationRepository  = app.injector.instanceOf[ApplicationRepository]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
  }

  override protected def afterEach(): Unit = {
    result(subscriptionRepository.collection.drop().toFuture(), timeout)
    result(applicationRepository.collection.drop().toFuture(), timeout)
    super.afterEach()
  }

  def http(request: => Request[Either[String, String], Any]): Response[Either[String, String]] = {
    val httpClient = SimpleHttpClient()
    val response   = httpClient.send(request)
    httpClient.close()
    response
  }

  Feature("Fetch all applications") {

    Scenario("Fetch all applications") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application1: ApplicationWithCollaborators = createApplication(awsApiGatewayApplicationName)

      When("We fetch all applications")
      val uri      = s"$serviceUrl/application"
      val response = http(basicRequest.get(uri"$uri"))

      response.code shouldBe StatusCode.Ok
      val result = Json.parse(response.body.value).as[Seq[ApplicationWithCollaborators]]

      Then("The application is returned in the result")
      result.exists(r => r.id == application1.id) shouldBe true
    }
  }

  Feature("Fetch an application") {

    Scenario("Fetch application from an application ID") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application: ApplicationWithCollaborators = createApplication()

      When("We fetch the application by its ID")
      val uri           = s"$serviceUrl/application/${application.id.value}"
      val fetchResponse = http(basicRequest.get(uri"$uri"))
      fetchResponse.code shouldBe StatusCode.Ok
      val result        = Json.parse(fetchResponse.body.value).as[ApplicationWithCollaborators]

      Then("The application is returned")
      result shouldBe application
    }

    Scenario("Fetch application from a collaborator email address") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A collaborator has access to two third party applications")
      val application1: ApplicationWithCollaborators = createApplication(applicationName1)
      val application2: ApplicationWithCollaborators = createApplication(applicationName2)

      When("We fetch the application by the collaborator email address")
      val uri           = s"$serviceUrl/application?emailAddress=$emailAddress"
      val fetchResponse = http(basicRequest.get(uri"$uri"))
      fetchResponse.code shouldBe StatusCode.Ok
      val result        = Json.parse(fetchResponse.body.value).as[Seq[ApplicationWithCollaborators]]

      Then("The applications are returned")
      result should contain theSameElementsAs Seq(application1, application2)
    }

    // TODO use commands
    Scenario("Fetch application credentials") {
      Given("No applications exist")
      emptyApplicationRepository()

      val appName = "appName"

      Given("A third party application")
      val application: ApplicationWithCollaborators = createApplication(appName)
      val cmd                                       =
        ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, UUID.randomUUID().toString, instant)

      sendApplicationCommand(cmd, application)
      val createdApp = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())

      When("We fetch the application credentials")
      val uri      = s"$serviceUrl/application/${application.id.value}/credentials"
      val response = http(basicRequest.get(uri"$uri"))
      response.code shouldBe StatusCode.Ok

      Then("The credentials are returned")
      // scalastyle:off magic.number
      val expectedClientSecrets = createdApp.tokens.production.clientSecrets

      val returnedResponse = Json.parse(response.body.value).as[ApplicationTokenResponse]
      returnedResponse.clientId should be(application.clientId)
      returnedResponse.accessToken.length should be(32)

      // Bug in JodaTime means we can't do a direct comparison between returnedResponse.production.clientSecrets and expectedClientSecrets
      // We have to compare contents individually
      val returnedClientSecret = returnedResponse.clientSecrets.head
      returnedClientSecret.name should be(expectedClientSecrets.head.name)
      returnedClientSecret.createdOn.toEpochMilli should be(expectedClientSecrets.head.createdOn.toEpochMilli)
    }
  }

  Feature("Validate Credentials") {
    def validationRequest(clientId: ClientId, clientSecret: String) =
      s"""
         | {
         |   "clientId": "${clientId.value}",
         |   "clientSecret": "$clientSecret"
         | }
         |""".stripMargin

    Scenario("Return details of application when valid") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application: ApplicationWithCollaborators = createApplication(awsApiGatewayApplicationName)

      emailStub.willPostEmailNotification()

      import com.github.t3hnar.bcrypt._
      val secret       = UUID.randomUUID().toString
      val hashedSecret = secret.bcrypt(4)
      val cmd          = ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, hashedSecret, instant)

      val addClientSecretResponse = sendApplicationCommand(cmd, application)
      addClientSecretResponse.code shouldBe StatusCode.Ok

      val createdApplication = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      val credentials        = createdApplication.tokens.production

      When("We attempt to validate the credentials")
      val requestBody        = validationRequest(credentials.clientId, secret)
      val validationResponse = sendJsonRequest("/application/credentials/validate", requestBody)

      Then("We get a successful response")
      validationResponse.code shouldBe StatusCode.Ok

      And("The application is returned")
      val returnedApplication = Json.parse(validationResponse.body.value).as[ApplicationWithCollaborators]
      returnedApplication shouldBe application.modify(_.copy(lastAccess = returnedApplication.details.lastAccess))
    }

    Scenario("Return UNAUTHORIZED if clientId is incorrect") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      createApplication(awsApiGatewayApplicationName)

      When("We attempt to validate the credentials")
      val requestBody        = validationRequest(ClientId("foo"), "bar")
      val validationResponse = sendJsonRequest(s"/application/credentials/validate", requestBody)

      Then("We get an UNAUTHORIZED response")
      validationResponse.code shouldBe StatusCode.Unauthorized
    }

    Scenario("Return UNAUTHORIZED if clientSecret is incorrect for valid clientId") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application: ApplicationWithCollaborators = createApplication(awsApiGatewayApplicationName)
      val createdApplication                        = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      val credentials                               = createdApplication.tokens.production

      When("We attempt to validate the credentials")
      val requestBody        = validationRequest(credentials.clientId, "bar")
      val validationResponse = sendJsonRequest("/application/credentials/validate", requestBody)

      Then("We get an UNAUTHORIZED response")
      validationResponse.code shouldBe StatusCode.Unauthorized
    }
  }

  Feature("Access.Privileged Applications") {

    val privilegedApplicationsScenario = "Create Access.Privileged application"
    Scenario(privilegedApplicationsScenario) {
      Given("No applications exist")
      emptyApplicationRepository()

      awsApiGatewayStub.willCreateOrUpdateApplication(awsApiGatewayApplicationName, "", RateLimitTier.BRONZE)
      val appName = "privileged-app-name"

      Given("The gatekeeper is logged in")
      authStub.willValidateLoggedInUserHasGatekeeperRole()

      And("Totp returns successfully")
      totpStub.willReturnTOTP(privilegedApplicationsScenario)

      When("We create a privileged application")
      val createdResponse = sendJsonRequest("/application", applicationRequest(appName, privilegedAccess))
      createdResponse.code shouldBe StatusCode.Created

      Then("The application is returned with the Totp Ids and the Totp Secrets")
      val totpIds     = (Json.parse(createdResponse.body.value) \ "details" \ "access" \ "totpIds").as[TotpId]
      val totpSecrets = (Json.parse(createdResponse.body.value) \ "totp").as[CreateApplicationResponse.TotpSecret]

      totpIds match {
        case TotpId("prod-id")    => totpSecrets shouldBe CreateApplicationResponse.TotpSecret("prod-secret")
        case TotpId("sandbox-id") => totpSecrets shouldBe CreateApplicationResponse.TotpSecret("sandbox-secret")
        case _                    => throw new IllegalStateException(s"Unexpected result - totpIds: $totpIds, totpSecrets: $totpSecrets")
      }
    }
  }

  Feature("Add/Remove collaborators to an application") {

    Scenario("Add collaborator for an application") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application  = createApplication()
      val newUserId    = UserId.random
      val newUserEmail = "bob@example.com".toLaxEmail

      emailStub.willPostEmailNotification()
      apiPlatformEventsStub.willReceiveEventType("COLLABORATOR_ADDED")

      When("We request to add the developer as a collaborator of the application")
      val addCollaboratorCommandPayload = s"""{
                                             |  "actor": {
                                             |    "email": "admin@example.com",
                                             |    "actorType": "COLLABORATOR"
                                             |  },
                                             |  "collaborator": {
                                             |    "emailAddress": "${newUserEmail.text}",
                                             |    "role": "DEVELOPER",
                                             |    "userId": "${newUserId.value}"
                                             |  },
                                             |  "updateType": "addCollaborator",
                                             |  "timestamp": "2020-01-01T12:00:00.000Z"
                                             | }""".stripMargin

      val response = sendJsonRequest(
        s"/application/${application.id.value}/dispatch",
        s"""{
           | "command": $addCollaboratorCommandPayload,
           | "verifiedCollaboratorsToNotify": []
           | }""".stripMargin,
        method = Method.PATCH
      )
      response.code shouldBe StatusCode.Ok
      val result   = Json.parse(response.body.value).as[DispatchResult]

      Then("The collaborator is added")
      inside(result) {
        case DispatchResult(appResponse, events) => {
          appResponse.collaborators should contain(Collaborators.Developer(newUserId, newUserEmail))
          events.size shouldBe 1
        }
      }

      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.collaborators should contain(Collaborators.Developer(newUserId, newUserEmail))
    }

    Scenario("Remove collaborator to an application") {
      emailStub.willPostEmailNotification()
      apiPlatformEventsStub.willReceiveEventType("COLLABORATOR_REMOVED")

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()

      When("We request to remove a collaborator to the application")
      val commandPayload = s"""{
                              |  "actor": {
                              |    "email": "admin@example.com",
                              |    "actorType": "COLLABORATOR"
                              |  },
                              |  "collaborator": {
                              |    "emailAddress": "$emailAddress",
                              |    "role": "DEVELOPER",
                              |    "userId": "${userId.value}"
                              |  },
                              |  "timestamp": "2020-01-01T12:00:00.000Z",
                              |  "updateType": "removeCollaborator"
                              | }""".stripMargin

      val response = sendJsonRequest(
        s"/application/${application.id.value}/dispatch",
        s"""{
           | "command": $commandPayload,
           | "verifiedCollaboratorsToNotify": []
           | }""".stripMargin,
        method = Method.PATCH
      )
      response.code shouldBe StatusCode.Ok
      val result   = Json.parse(response.body.value).as[DispatchResult]

      Then("The collaborator is removed")
      inside(result) {
        case DispatchResult(appResponse, events) => {
          appResponse.collaborators should not contain (Collaborators.Developer(userId, emailAddress.toLaxEmail))
          events.size shouldBe 1
        }
      }

      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.collaborators should not contain emailAddress.developer(userId)
    }
  }

  Feature("Update an application") {

    Scenario("Add two client secrets then remove the last one") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()
      apiPlatformEventsStub.willReceiveClientSecretAddedEvent()
      apiPlatformEventsStub.willReceiveClientRemovedEvent()
      emailStub.willPostEmailNotification()
      val createdApp  = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      createdApp.tokens.production.clientSecrets should have size 0

      When("I request to add a production client secret")
      val cmd =
        ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, UUID.randomUUID().toString, instant)

      val cmdResponse = sendApplicationCommand(cmd, application)
      cmdResponse.code shouldBe StatusCode.Ok

      Then("The client secret is added to the production environment of the application")
      apiPlatformEventsStub.verifyClientSecretAddedEventSent()
      val uri                                     = s"$serviceUrl/application/${application.id.value}/credentials"
      val firstFetchResponse                      = http(basicRequest.get(uri"$uri"))
      val firstResponse: ApplicationTokenResponse = Json.parse(firstFetchResponse.body.value).as[ApplicationTokenResponse]
      val secrets: List[ClientSecretResponse]     = firstResponse.clientSecrets
      secrets should have size 1

      When("I request to add a second production client secret")
      val secondCmd         =
        ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, UUID.randomUUID().toString, instant)
      val secondCmdResponse = sendApplicationCommand(secondCmd, application)
      secondCmdResponse.code shouldBe StatusCode.Ok
      // check secret was added

      Then("The client secret is added to the production environment of the application")
      val uri2                                     = s"$serviceUrl/application/${application.id.value}/credentials"
      val secondFetchResponse                      = http(basicRequest.get(uri"$uri2"))
      val secondResponse: ApplicationTokenResponse = Json.parse(secondFetchResponse.body.value).as[ApplicationTokenResponse]
      val moreSecrets: List[ClientSecretResponse]  = secondResponse.clientSecrets

      moreSecrets should have size 2

      When("I request to remove a production client secret")
      val removeCmd         = ApplicationCommands.RemoveClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), secondCmd.id, instant)
      val removeCmdResponse = sendApplicationCommand(removeCmd, application)
      removeCmdResponse.code shouldBe StatusCode.Ok

      apiPlatformEventsStub.verifyClientSecretRemovedEventSent()
    }

    Scenario("Delete an application") {
      apiSubscriptionFieldsStub.willDeleteTheSubscriptionFields()
      thirdPartyDelegatedAuthorityStub.willRevokeApplicationAuthorities()
      awsApiGatewayStub.willDeleteApplication(awsApiGatewayApplicationName)
      emailStub.willPostEmailNotification()

      Given("No applications exist")
      emptyApplicationRepository()

      Given("The gatekeeper is logged in")
      authStub.willValidateLoggedInUserHasGatekeeperRole()

      And("A third party application")
      val application = createApplication()

      When("I request to delete the application")
      val deleteResponse = sendJsonRequest(
        path = s"/application/${application.id.value}/delete",
        data = s"""{"gatekeeperUserId": "$gatekeeperUserId", "requestedByEmailAddress": "$emailAddress"}""",
        extraHeaders = Seq(Header.authorization("Bearer", UUID.randomUUID.toString))
      )
      deleteResponse.code shouldBe StatusCode.NoContent

      Then("The application is deleted")
      val uri           = s"$serviceUrl/application/${application.id.value}"
      val fetchResponse = http(basicRequest.get(uri"$uri"))
      fetchResponse.code shouldBe StatusCode.NotFound
    }

  }

  Feature("Subscription") {

    Scenario("Fetch API Subscriptions") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()

      And("The application is subscribed to an API")
      result(subscriptionExists(application.id, context, version), timeout)

      When("I fetch the API subscriptions of the application")
      val uri      = s"$serviceUrl/application/${application.id.value}/subscription"
      val response = http(basicRequest.get(uri"$uri"))

      Then("The API subscription is returned")
      val actualApiSubscription = Json.parse(response.body.value).as[Set[ApiIdentifier]]
      actualApiSubscription shouldBe Set(ApiIdentifier(context, version))
    }

    Scenario("Fetch All API Subscriptions") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication("App with subscription")

      And("I subscribe the application to an API")
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()
      val cmd = ApplicationCommands.SubscribeToApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), instant)

      val subscribeResponse = sendApplicationCommand(cmd, application)

      And("The subscription is created")
      subscribeResponse.code shouldBe StatusCode.Ok

      When("I fetch all API subscriptions")
      val uri      = s"$serviceUrl/application/subscriptions"
      val response = http(basicRequest.get(uri"$uri"))

      Then("The result includes the new subscription")
      val result = Json.parse(response.body.value).as[Seq[SubscriptionData]]
      result should have size 1

      val subscribedApps = result.head.applications
      subscribedApps should have size 1
      subscribedApps.head shouldBe application.id
    }

    Scenario("Subscribe to an api") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()

      When("I request to subscribe the application to the API")
      val cmd = ApplicationCommands.SubscribeToApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), instant)

      val response = sendApplicationCommand(cmd, application)

      Then("A 200 is returned")
      response.code shouldBe StatusCode.Ok

      apiPlatformEventsStub.verifyApiSubscribedEventSent()
    }

    Scenario("Unsubscribe to an api") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()

      Given("I have subscribed the application to the API")
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()
      val subcmd = ApplicationCommands.SubscribeToApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), instant)
      sendApplicationCommand(subcmd, application)

      When("I request to unsubscribe the application from an API")
      apiPlatformEventsStub.willReceiveApiUnsubscribedEvent()
      val cmd      = ApplicationCommands.UnsubscribeFromApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), instant)
      val response = sendApplicationCommand(cmd, application)

      Then("A 200 is returned")
      response.code shouldBe StatusCode.Ok

      apiPlatformEventsStub.verifyApiUnsubscribedEventSent()
    }
  }

  Feature("Grant Length") {
    Scenario("change grant length for an application") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()
      application.details.grantLength shouldBe GrantLength.EIGHTEEN_MONTHS

      Given("The gatekeeper is logged in")
      authStub.willValidateLoggedInUserHasGatekeeperRole("admin@example.com")

      Given("I have updated the grant length to six months")
      apiPlatformEventsStub.willReceiveChangeGrantLengthEvent()

      val subcmd   = ApplicationCommands.ChangeGrantLength("admin@example.com", instant, GrantLength.SIX_MONTHS)
      val response = sendApplicationCommand(subcmd, application, Seq(Header.authorization("Bearer", UUID.randomUUID.toString)))
      response.body.value.contains("\"newGrantLengthInDays\":180") shouldBe true

      Then("A 200 is returned")
      response.code shouldBe StatusCode.Ok

      apiPlatformEventsStub.verifyGrantLengthChangedEventSent()
    }
  }

  Feature("Rate Limit") {
    Scenario("change ratelimit for an application") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application: ApplicationWithCollaborators = createApplication()
      application.details.rateLimitTier shouldBe RateLimitTier.BRONZE

      Given("The gatekeeper is logged in")
      authStub.willValidateLoggedInUserHasGatekeeperRole("admin@example.com")

      Given("I have updated the rate limit to GOLD")
      apiPlatformEventsStub.willReceiveChangeRateLimitEvent()
      val subcmd   = ApplicationCommands.ChangeRateLimitTier("admin@example.com", instant, RateLimitTier.GOLD)
      val response = sendApplicationCommand(subcmd, application, Seq(Header.authorization("Bearer", UUID.randomUUID.toString)))
      response.body.value.contains("\"rateLimitTier\":\"GOLD\"") shouldBe true

      Then("A 200 is returned")
      response.code shouldBe StatusCode.Ok

      apiPlatformEventsStub.verifyRatelLimitChangedEventSent()
    }
  }

  Feature("Application name validation") {
    Scenario("for the invalid name 'HMRC'") {
      When("I request if a name is invalid")

      val nameToCheck = "my invalid app name HMRC"

      val requestBody = Json.obj("nameToValidate" -> nameToCheck).toString
      val result      = sendJsonRequest("/application/name/validate", requestBody)

      Then("The response should be OK")
      result.code shouldBe StatusCode.Ok

      Then("The response should not contain any errors")

      result.body.value shouldBe Json.obj("validationResult" -> "INVALID").toString
    }
  }

  private def fetchApplication(id: ApplicationId): ApplicationWithCollaborators = {
    val uri             = s"$serviceUrl/application/${id.value.toString}"
    val fetchedResponse = http(basicRequest.get(uri"$uri"))
    fetchedResponse.code shouldBe StatusCode.Ok
    Json.parse(fetchedResponse.body.value).as[ApplicationWithCollaborators]
  }

  private def emptyApplicationRepository() = {
    ready(applicationRepository.collection.drop().toFuture(), timeout)
  }

  private def createApplication(appName: String = applicationName1, access: Access = standardAccess): ApplicationWithCollaborators = {
    awsApiGatewayStub.willCreateOrUpdateApplication(awsApiGatewayApplicationName, "", RateLimitTier.BRONZE)
    val createdResponse = sendJsonRequest("/application", applicationRequest(appName, access))
    createdResponse.code shouldBe StatusCode.Created
    Json.parse(createdResponse.body.value).as[ApplicationWithCollaborators]
  }

  private def subscriptionExists(applicationId: ApplicationId, apiContext: ApiContext, apiVersion: ApiVersionNbr) = {
    subscriptionRepository.add(applicationId, new ApiIdentifier(apiContext, apiVersion))
  }

  private def sendJsonRequest(path: String, data: String, extraHeaders: Seq[Header] = Seq.empty[Header], method: Method = Method.POST): Response[Either[String, String]] = {
    val uri = s"$serviceUrl$path"
    http(
      basicRequest
        .method(method, uri"$uri")
        .contentType("application/json")
        .headers(extraHeaders: _*)
        .body(data)
    )
  }

  def sendApplicationCommand(cmd: ApplicationCommand, application: ApplicationWithCollaborators, extraHeaders: Seq[Header] = Seq()): Response[Either[String, String]] = {
    val request                                   = DispatchRequest(cmd, Set.empty)
    implicit val writer: OWrites[DispatchRequest] = Json.writes[DispatchRequest]
    sendJsonRequest(s"/application/${application.id}/dispatch", Json.toJson(request).toString(), extraHeaders, Method.PATCH)
  }

  private def applicationRequest(name: String, access: Access) = {
    s"""{
       |"name" : "$name",
       |"environment" : "PRODUCTION",
       |"description" : "Some Description",
       |"access" : ${Json.toJson(access)},
       |"collaborators": [
       | {
       |   "emailAddress": "admin@example.com",
       |   "role": "ADMINISTRATOR",
       |   "userId": "${adminUserId.value}"
       | },
       | {
       |   "emailAddress": "$emailAddress",
       |   "role": "DEVELOPER",
       |   "userId": "${userId.value}"
       | }
       |]
       |}""".stripMargin.replaceAll("\n", "")
  }
}
