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

import play.api.Application
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.Status._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import scalaj.http.{Http, HttpResponse}
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientId, ClientSecret, Collaborators}

import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.Await.{ready, result}
import scala.util.Random
import uk.gov.hmrc.thirdpartyapplication.util.CollaboratorTestData
import uk.gov.hmrc.thirdpartyapplication.controllers.ApplicationCommandController._
import org.scalatest.Inside
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, ApplicationCommands}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

class DummyCredentialGenerator extends CredentialGenerator {
  override def generate() = "a" * 10
}

class ThirdPartyApplicationComponentISpec extends BaseFeatureSpec with CollaboratorTestData with Inside {

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

  override def fakeApplication: Application = {
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

  val standardAccess   = Standard(
    redirectUris = List("http://example.com/redirect"),
    termsAndConditionsUrl = Some("http://example.com/terms"),
    privacyPolicyUrl = Some("http://example.com/privacy"),
    overrides = Set.empty
  )
  val privilegedAccess = Privileged(totpIds = None, scopes = Set("ogdScope"))

  lazy val subscriptionRepository = app.injector.instanceOf[SubscriptionRepository]
  lazy val applicationRepository  = app.injector.instanceOf[ApplicationRepository]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
  }

  override protected def afterEach(): Unit = {
    result(subscriptionRepository.collection.drop.toFuture(), timeout)
    result(applicationRepository.collection.drop.toFuture(), timeout)
    super.afterEach()
  }

  Feature("Fetch all applications") {

    Scenario("Fetch all applications") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application1: ApplicationResponse = createApplication(awsApiGatewayApplicationName)

      When("We fetch all applications")
      val fetchResponse = Http(s"$serviceUrl/application").asString
      fetchResponse.code shouldBe OK
      val result        = Json.parse(fetchResponse.body).as[Seq[ApplicationResponse]]

      Then("The application is returned in the result")
      result.exists(r => r.id == application1.id) shouldBe true
    }
  }

  Feature("Fetch an application") {

    Scenario("Fetch application from an application ID") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application: ApplicationResponse = createApplication()

      When("We fetch the application by its ID")
      val fetchResponse = Http(s"$serviceUrl/application/${application.id.value}").asString
      fetchResponse.code shouldBe OK
      val result        = Json.parse(fetchResponse.body).as[ApplicationResponse]

      Then("The application is returned")
      result shouldBe application
    }

    Scenario("Fetch application from a collaborator email address") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A collaborator has access to two third party applications")
      val application1: ApplicationResponse = createApplication(applicationName1)
      val application2: ApplicationResponse = createApplication(applicationName2)

      When("We fetch the application by the collaborator email address")
      val fetchResponse = Http(s"$serviceUrl/application?emailAddress=$emailAddress").asString
      fetchResponse.code shouldBe OK
      val result        = Json.parse(fetchResponse.body).as[Seq[ApplicationResponse]]

      Then("The applications are returned")
      result should contain theSameElementsAs Seq(application1, application2)
    }

    // TODO use commands
    Scenario("Fetch application credentials") {
      Given("No applications exist")
      emptyApplicationRepository()

      val appName = "appName"

      Given("A third party application")
      val application: ApplicationResponse = createApplication(appName)
      val cmd                              =
        ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, UUID.randomUUID().toString, FixedClock.now)

      sendApplicationCommand(cmd, application)
      val createdApp = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())

      When("We fetch the application credentials")
      val response = Http(s"$serviceUrl/application/${application.id.value}/credentials").asString
      response.code shouldBe OK

      Then("The credentials are returned")
      // scalastyle:off magic.number
      val expectedClientSecrets = createdApp.tokens.production.clientSecrets

      val returnedResponse = Json.parse(response.body).as[ApplicationTokenResponse]
      returnedResponse.clientId should be(application.clientId)
      returnedResponse.accessToken.length should be(32)

      // Bug in JodaTime means we can't do a direct comparison between returnedResponse.production.clientSecrets and expectedClientSecrets
      // We have to compare contents individually
      val returnedClientSecret = returnedResponse.clientSecrets.head
      returnedClientSecret.name should be(expectedClientSecrets.head.name)
      returnedClientSecret.secret.isDefined should be(false)
      returnedClientSecret.createdOn.toInstant(ZoneOffset.UTC).toEpochMilli should be(expectedClientSecrets.head.createdOn.toInstant(ZoneOffset.UTC).toEpochMilli)
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
      val application: ApplicationResponse = createApplication(awsApiGatewayApplicationName)

      emailStub.willPostEmailNotification()

      import com.github.t3hnar.bcrypt._
      val secret       = UUID.randomUUID().toString
      val hashedSecret = secret.bcrypt(4)
      val cmd          = ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, hashedSecret, FixedClock.now)

      val addClientSecretResponse = sendApplicationCommand(cmd, application)
      addClientSecretResponse.code shouldBe OK

      val createdApplication = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      val credentials        = createdApplication.tokens.production

      When("We attempt to validate the credentials")
      val requestBody        = validationRequest(credentials.clientId, secret)
      val validationResponse = postData("/application/credentials/validate", requestBody)

      Then("We get a successful response")
      validationResponse.code shouldBe OK

      And("The application is returned")
      val returnedApplication = Json.parse(validationResponse.body).as[ApplicationResponse]
      returnedApplication shouldBe application.copy(lastAccess = returnedApplication.lastAccess)
    }

    Scenario("Return UNAUTHORIZED if clientId is incorrect") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      createApplication(awsApiGatewayApplicationName)

      When("We attempt to validate the credentials")
      val requestBody        = validationRequest(ClientId("foo"), "bar")
      val validationResponse = postData(s"/application/credentials/validate", requestBody)

      Then("We get an UNAUTHORIZED response")
      validationResponse.code shouldBe UNAUTHORIZED
    }

    Scenario("Return UNAUTHORIZED if clientSecret is incorrect for valid clientId") {
      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application: ApplicationResponse = createApplication(awsApiGatewayApplicationName)
      val createdApplication               = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      val credentials                      = createdApplication.tokens.production

      When("We attempt to validate the credentials")
      val requestBody        = validationRequest(credentials.clientId, "bar")
      val validationResponse = postData("/application/credentials/validate", requestBody)

      Then("We get an UNAUTHORIZED response")
      validationResponse.code shouldBe UNAUTHORIZED
    }
  }

  Feature("Privileged Applications") {

    val privilegedApplicationsScenario = "Create Privileged application"
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
      val createdResponse = postData("/application", applicationRequest(appName, privilegedAccess))
      createdResponse.code shouldBe CREATED

      Then("The application is returned with the Totp Ids and the Totp Secrets")
      val totpIds     = (Json.parse(createdResponse.body) \ "access" \ "totpIds").as[TotpId]
      val totpSecrets = (Json.parse(createdResponse.body) \ "totp").as[TotpSecret]

      totpIds match {
        case TotpId("prod-id")    => totpSecrets shouldBe TotpSecret("prod-secret")
        case TotpId("sandbox-id") => totpSecrets shouldBe TotpSecret("sandbox-secret")
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
                                             |  "timestamp": "2020-01-01T12:00:00",
                                             |  "updateType": "addCollaborator"
                                             | }""".stripMargin

      val response = postData(
        s"/application/${application.id.value}/dispatch",
        s"""{
           | "command": $addCollaboratorCommandPayload,
           | "verifiedCollaboratorsToNotify": []
           | }""".stripMargin,
        method = "PATCH"
      )
      response.code shouldBe OK
      val result   = Json.parse(response.body).as[DispatchResult]

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
                              |  "timestamp": "2020-01-01T12:00:00",
                              |  "updateType": "removeCollaborator"
                              | }""".stripMargin

      val response = postData(
        s"/application/${application.id.value}/dispatch",
        s"""{
           | "command": $commandPayload,
           | "verifiedCollaboratorsToNotify": []
           | }""".stripMargin,
        method = "PATCH"
      )
      response.code shouldBe OK
      val result   = Json.parse(response.body).as[DispatchResult]

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

    Scenario("Update an application") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val originalOverrides: Set[OverrideFlag] = Set(
        PersistLogin,
        GrantWithoutConsent(Set("scope")),
        SuppressIvForAgents(Set("scope")),
        SuppressIvForOrganisations(Set("scope")),
        SuppressIvForIndividuals(Set("Scope"))
      )
      val application                          = createApplication(access = standardAccess.copy(overrides = originalOverrides))

      When("I request to update the application")
      val newApplicationName           = "My Renamed Application"
      val updatedRedirectUris          = List("http://example.com/redirect2", "http://example.com/redirect3")
      val updatedTermsAndConditionsUrl = Some("http://example.com/terms2")
      val updatedPrivacyPolicyUrl      = Some("http://example.com/privacy2")
      val updatedAccess                = Standard(
        redirectUris = updatedRedirectUris,
        termsAndConditionsUrl = updatedTermsAndConditionsUrl,
        privacyPolicyUrl = updatedPrivacyPolicyUrl,
        overrides = Set.empty
      )
      val updatedResponse              = postData(s"/application/${application.id.value}", applicationRequest(name = newApplicationName, access = updatedAccess))
      updatedResponse.code shouldBe OK

      Then("The application is updated but preserving the original access override flags")
      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.name shouldBe newApplicationName
      fetchedApplication.redirectUris shouldBe updatedRedirectUris
      fetchedApplication.termsAndConditionsUrl shouldBe updatedTermsAndConditionsUrl
      fetchedApplication.privacyPolicyUrl shouldBe updatedPrivacyPolicyUrl
      val fetchedAccess      = fetchedApplication.access.asInstanceOf[Standard]
      fetchedAccess.redirectUris shouldBe updatedRedirectUris
      fetchedAccess.termsAndConditionsUrl shouldBe updatedTermsAndConditionsUrl
      fetchedAccess.privacyPolicyUrl shouldBe updatedPrivacyPolicyUrl
      fetchedAccess.overrides shouldBe originalOverrides
    }

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
        ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, UUID.randomUUID().toString, FixedClock.now)

      val cmdResponse = sendApplicationCommand(cmd, application)
      cmdResponse.code shouldBe OK

      Then("The client secret is added to the production environment of the application")
      apiPlatformEventsStub.verifyClientSecretAddedEventSent()
      val firstFetchResponse                      = Http(s"$serviceUrl/application/${application.id.value}/credentials").asString
      val firstResponse: ApplicationTokenResponse = Json.parse(firstFetchResponse.body).as[ApplicationTokenResponse]
      val secrets: List[ClientSecretResponse]     = firstResponse.clientSecrets
      secrets should have size 1

      When("I request to add a second production client secret")
      val secondCmd         =
        ApplicationCommands.AddClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), "name", ClientSecret.Id.random, UUID.randomUUID().toString, FixedClock.now)
      val secondCmdResponse = sendApplicationCommand(secondCmd, application)
      secondCmdResponse.code shouldBe OK
      // check secret was added

      Then("The client secret is added to the production environment of the application")
      val secondFetchResponse                      = Http(s"$serviceUrl/application/${application.id.value}/credentials").asString
      val secondResponse: ApplicationTokenResponse = Json.parse(secondFetchResponse.body).as[ApplicationTokenResponse]
      val moreSecrets: List[ClientSecretResponse]  = secondResponse.clientSecrets

      moreSecrets should have size 2

      val clientSecretId: ClientSecret.Id = moreSecrets.last.id

      When("I request to remove a production client secret")
      val removeCmd         = ApplicationCommands.RemoveClientSecret(Actors.AppCollaborator("admin@example.com".toLaxEmail), secondCmd.id, FixedClock.now)
      val removeCmdResponse = sendApplicationCommand(removeCmd, application)
      removeCmdResponse.code shouldBe OK

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
      val deleteResponse = postData(
        path = s"/application/${application.id.value}/delete",
        data = s"""{"gatekeeperUserId": "$gatekeeperUserId", "requestedByEmailAddress": "$emailAddress"}""",
        extraHeaders = Seq(AUTHORIZATION -> UUID.randomUUID.toString)
      )
      deleteResponse.code shouldBe NO_CONTENT

      Then("The application is deleted")
      val fetchResponse = Http(s"$serviceUrl/application/${application.id.value}").asString
      fetchResponse.code shouldBe NOT_FOUND
    }

    Scenario("Change rate limit tier for an application") {

      Given("The gatekeeper is logged in")
      authStub.willValidateLoggedInUserHasGatekeeperRole()

      Given("No applications exist")
      emptyApplicationRepository()

      And("A third party application with BRONZE rate limit tier exists")
      val application = createApplication()

      And("AWS API Gateway is updated")
      awsApiGatewayStub.willCreateOrUpdateApplication(application.name, "", RateLimitTier.SILVER)

      Then("The response is successful")
      val response = postData(path = s"/application/${application.id.value}/rate-limit-tier", data = """{ "rateLimitTier" : "SILVER" }""")
      response.code shouldBe NO_CONTENT
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
      val response = Http(s"$serviceUrl/application/${application.id.value}/subscription").asString

      Then("The API subscription is returned")
      val actualApiSubscription = Json.parse(response.body).as[Set[ApiIdentifier]]
      actualApiSubscription shouldBe Set(ApiIdentifier(context, version))
    }

    Scenario("Fetch All API Subscriptions") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication("App with subscription")

      And("I subscribe the application to an API")
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()
      val cmd = ApplicationCommands.SubscribeToApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), FixedClock.now)

      val subscribeResponse = sendApplicationCommand(cmd, application)

      And("The subscription is created")
      subscribeResponse.code shouldBe OK

      When("I fetch all API subscriptions")
      val response = Http(s"$serviceUrl/application/subscriptions").asString

      Then("The result includes the new subscription")
      val result = Json.parse(response.body).as[Seq[SubscriptionData]]
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
      val cmd = ApplicationCommands.SubscribeToApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), FixedClock.now)

      val response = sendApplicationCommand(cmd, application)

      Then("A 200 is returned")
      response.code shouldBe OK

      apiPlatformEventsStub.verifyApiSubscribedEventSent()
    }

    Scenario("Unsubscribe to an api") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()

      Given("I have subscribed the application to the API")
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()
      val subcmd = ApplicationCommands.SubscribeToApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), FixedClock.now)
      sendApplicationCommand(subcmd, application)

      When("I request to unsubscribe the application from an API")
      apiPlatformEventsStub.willReceiveApiUnsubscribedEvent()
      val cmd      = ApplicationCommands.UnsubscribeFromApi(Actors.AppCollaborator("admin@example.com".toLaxEmail), ApiIdentifier(context, version), FixedClock.now)
      val response = sendApplicationCommand(cmd, application)

      Then("A 200 is returned")
      response.code shouldBe OK

      apiPlatformEventsStub.verifyApiUnsubscribedEventSent()
    }
  }

  Feature("Uplift") {

    Scenario("Request uplift for an application") {

      Given("No applications exist")
      emptyApplicationRepository()

      Given("A third party application")
      val application = createApplication()

      When("I request to uplift an application to production")
      val result = postData(
        s"/application/${application.id.value}/request-uplift",
        s"""{"requestedByEmailAddress":"admin@example.com", "applicationName": "Prod Application Name"}"""
      )

      Then("The application is updated to PENDING_GATEKEEPER_APPROVAL")
      result.code shouldBe NO_CONTENT
      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.state.name shouldBe State.PENDING_GATEKEEPER_APPROVAL
      fetchedApplication.name shouldBe "Prod Application Name"
    }
  }

  Feature("Application name validation") {
    Scenario("for the invalid name 'HMRC'") {
      When("I request if a name is invalid")

      val nameToCheck = "my invalid app name HMRC"

      val requestBody = Json.obj("applicationName" -> nameToCheck).toString
      val result      = postData("/application/name/validate", requestBody)

      Then("The response should be OK")
      result.code shouldBe OK

      Then("The response should not contain any errors")

      result.body shouldBe Json.obj("errors" -> Json.obj("invalidName" -> true, "duplicateName" -> false)).toString
    }
  }

  private def fetchApplication(id: ApplicationId): ApplicationResponse = {
    val fetchedResponse = Http(s"$serviceUrl/application/${id.value.toString}").asString
    fetchedResponse.code shouldBe OK
    Json.parse(fetchedResponse.body).as[ApplicationResponse]
  }

  private def emptyApplicationRepository() = {
    ready(applicationRepository.collection.drop().toFuture(), timeout)
  }

  private def createApplication(appName: String = applicationName1, access: Access = standardAccess): ApplicationResponse = {
    awsApiGatewayStub.willCreateOrUpdateApplication(awsApiGatewayApplicationName, "", RateLimitTier.BRONZE)
    val createdResponse = postData("/application", applicationRequest(appName, access))
    createdResponse.code shouldBe CREATED
    Json.parse(createdResponse.body).as[ApplicationResponse]
  }

  private def subscriptionExists(applicationId: ApplicationId, apiContext: ApiContext, apiVersion: ApiVersion) = {
    subscriptionRepository.add(applicationId, new ApiIdentifier(apiContext, apiVersion))
  }

  private def postData(path: String, data: String, method: String = "POST", extraHeaders: Seq[(String, String)] = Seq()): HttpResponse[String] = {
    val connTimeoutMs = 5000
    val readTimeoutMs = 10000
    Http(s"$serviceUrl$path").postData(data).method(method)
      .header("Content-Type", "application/json")
      .headers(extraHeaders)
      .timeout(connTimeoutMs, readTimeoutMs)
      .asString
  }

  def sendApplicationCommand(cmd: ApplicationCommand, application: ApplicationResponse): HttpResponse[String] = {
    val request         = DispatchRequest(cmd, Set.empty)
    implicit val writer = Json.writes[DispatchRequest]
    postData(s"/application/${application.id.value}/dispatch", Json.toJson(request).toString(), "PATCH")
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
