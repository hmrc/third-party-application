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

package it.uk.gov.hmrc.thirdpartyapplication.component

import java.util.UUID

import org.joda.time.DateTimeUtils
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.Status._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import scalaj.http.{Http, HttpResponse}
import uk.gov.hmrc.thirdpartyapplication.controllers.AddCollaboratorResponse
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator

import scala.concurrent.Await.result
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class DummyCredentialGenerator extends CredentialGenerator {
  override def generate() = "a" * 10
}

class ThirdPartyApplicationComponentSpec extends BaseFeatureSpec {

  override def fakeApplication = 
    GuiceApplicationBuilder()
        .configure(Map("Test.disableAwsCalls" -> false, "appName" -> "third-party-application"))
        .overrides(bind[CredentialGenerator].to[DummyCredentialGenerator])
        .disable(classOf[SchedulerModule])
        .build()

  val applicationName1 = "My 1st Application"
  val applicationName2 = "My 2nd Application"
  val emailAddress = "user@example.com"
  val gatekeeperUserId = "gate.keeper"
  val username = "a" * 10
  val password = "a" * 10
  val awsApiGatewayApplicationName = "a" * 10
  val testCookieLength = 10
  val cookie = Random.alphanumeric.take(testCookieLength).mkString
  val serviceName = "service"
  val apiName = "apiName"
  val context = "myapi"
  val version = "1.0"
  val anApiDefinition = ApiDefinition(serviceName, apiName, context, List(ApiVersion(version, ApiStatus.STABLE, None)), None)
  val standardAccess = Standard(
    redirectUris = List("http://example.com/redirect"),
    termsAndConditionsUrl = Some("http://example.com/terms"),
    privacyPolicyUrl = Some("http://example.com/privacy"),
    overrides = Set.empty
  )
  val privilegedAccess = Privileged(totpIds = None, scopes = Set("ogdScope"))

  lazy val subscriptionRepository = app.injector.instanceOf[SubscriptionRepository]
  lazy val applicationRepository = app.injector.instanceOf[ApplicationRepository]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    result(applicationRepository.removeAll(), timeout)

    DateTimeUtils.setCurrentMillisFixed(DateTimeUtils.currentTimeMillis())
  }

  override protected def afterEach(): Unit = {
    DateTimeUtils.setCurrentMillisSystem()
    result(subscriptionRepository.removeAll(), timeout)
    result(applicationRepository.removeAll(), timeout)
    super.afterEach()
  }

  feature("Fetch all applications") {

    scenario("Fetch all applications") {

      Given("A third party application")
      val application1: ApplicationResponse = createApplication(awsApiGatewayApplicationName)

      When("We fetch all applications")
      val fetchResponse = Http(s"$serviceUrl/application").asString
      fetchResponse.code shouldBe OK
      val result = Json.parse(fetchResponse.body).as[Seq[ApplicationResponse]]

      Then("The application is returned in the result")
      result.exists(r => r.id == application1.id) shouldBe true
    }
  }

  feature("Fetch an application") {

    scenario("Fetch application from an application ID") {

      Given("A third party application")
      val application: ApplicationResponse = createApplication()

      When("We fetch the application by its ID")
      val fetchResponse = Http(s"$serviceUrl/application/${application.id}").asString
      fetchResponse.code shouldBe OK
      val result = Json.parse(fetchResponse.body).as[ApplicationResponse]

      Then("The application is returned")
      result shouldBe application
    }

    scenario("Fetch application from a collaborator email address") {

      Given("A collaborator has access to two third party applications")
      val application1: ApplicationResponse = createApplication(applicationName1)
      val application2: ApplicationResponse = createApplication(applicationName2)

      When("We fetch the application by the collaborator email address")
      val fetchResponse = Http(s"$serviceUrl/application?emailAddress=$emailAddress").asString
      fetchResponse.code shouldBe OK
      val result = Json.parse(fetchResponse.body).as[Seq[ApplicationResponse]]

      Then("The applications are returned")
      result should contain theSameElementsAs Seq(application1, application2)
    }

    scenario("Fetch application credentials") {
      val appName = "appName"

      Given("A third party application")
      val application: ApplicationResponse = createApplication(appName)
      postData(s"/application/${application.id}/client-secret", s"""{"actorEmailAddress": "$emailAddress"}""")
      val createdApp = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())

      When("We fetch the application credentials")
      val response = Http(s"$serviceUrl/application/${application.id}/credentials").asString
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
      returnedClientSecret.createdOn.getMillis should be(expectedClientSecrets.head.createdOn.getMillis)
    }
  }

  feature("Validate Credentials") {
    def validationRequest(clientId: String, clientSecret: String) =
      s"""
         | {
         |   "clientId": "$clientId",
         |   "clientSecret": "$clientSecret"
         | }
         |""".stripMargin

    scenario("Return details of application when valid") {
      Given("A third party application")
      val application: ApplicationResponse = createApplication(awsApiGatewayApplicationName)
      val clientSecretCreationResponse = postData(s"/application/${application.id}/client-secret", s"""{"actorEmailAddress": "$emailAddress"}""")
      val applicationToken = Json.parse(clientSecretCreationResponse.body).as[ApplicationTokenResponse]

      val createdApplication = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      val credentials = createdApplication.tokens.production

      When("We attempt to validate the credentials")
      val requestBody = validationRequest(credentials.clientId, applicationToken.clientSecrets.head.secret.get)
      val validationResponse = postData(s"/application/credentials/validate", requestBody)

      Then("We get a successful response")
      validationResponse.code shouldBe OK

      And("The application is returned")
      val returnedApplication = Json.parse(validationResponse.body).as[ApplicationResponse]
      returnedApplication shouldBe application
    }

    scenario("Return UNAUTHORIZED if clientId is incorrect") {
      Given("A third party application")
      val application: ApplicationResponse = createApplication(awsApiGatewayApplicationName)

      When("We attempt to validate the credentials")
      val requestBody = validationRequest("foo", "bar")
      val validationResponse = postData(s"/application/credentials/validate", requestBody)

      Then("We get an UNAUTHORIZED response")
      validationResponse.code shouldBe UNAUTHORIZED
    }

    scenario("Return UNAUTHORIZED if clientSecret is incorrect for valid clientId") {
      Given("A third party application")
      val application: ApplicationResponse = createApplication(awsApiGatewayApplicationName)
      val createdApplication = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      val credentials = createdApplication.tokens.production

      When("We attempt to validate the credentials")
      val requestBody = validationRequest(credentials.clientId, "bar")
      val validationResponse = postData(s"/application/credentials/validate", requestBody)

      Then("We get an UNAUTHORIZED response")
      validationResponse.code shouldBe UNAUTHORIZED
    }
  }

  feature("Privileged Applications") {

    val privilegedApplicationsScenario = "Create Privileged application"
    scenario(privilegedApplicationsScenario) {
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
      val totpIds = (Json.parse(createdResponse.body) \ "access" \ "totpIds").as[TotpIds]
      val totpSecrets = (Json.parse(createdResponse.body) \ "totp").as[TotpSecrets]

      totpIds match {
        case TotpIds("prod-id") => totpSecrets shouldBe TotpSecrets("prod-secret")
        case TotpIds("sandbox-id") => totpSecrets shouldBe TotpSecrets("sandbox-secret")
        case _ => throw new IllegalStateException(s"Unexpected result - totpIds: $totpIds, totpSecrets: $totpSecrets")
      }
    }
  }

  feature("Add/Remove collaborators to an application") {

    scenario("Add collaborator for an application") {

      Given("A third party application")
      val application = createApplication()
      apiPlatformEventsStub.willReceiveTeamMemberAddedEvent()

      When("We request to add the developer as a collaborator of the application")
      val response = postData(s"/application/${application.id}/collaborator",
        """{
          | "adminEmail":"admin@example.com",
          | "collaborator": {
          |   "emailAddress": "test@example.com",
          |   "role":"ADMINISTRATOR"
          | },
          | "isRegistered": true,
          | "adminsToEmail": []
          | }""".stripMargin)
      response.code shouldBe OK
      val result = Json.parse(response.body).as[AddCollaboratorResponse]

      Then("The collaborator is added")
      result shouldBe AddCollaboratorResponse(registeredUser = true)
      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.collaborators should contain(Collaborator("test@example.com", Role.ADMINISTRATOR))

      apiPlatformEventsStub.verifyTeamMemberAddedEventSent()
    }

    scenario("Remove collaborator to an application") {
      emailStub.willPostEmailNotification()
      apiPlatformEventsStub.willReceiveTeamMemberRemovedEvent()

      Given("A third party application")
      val application = createApplication()

      When("We request to remove a collaborator to the application")
      val response = Http(s"$serviceUrl/application/${application.id}/collaborator/user@example.com?admin=admin@example.com&adminsToEmail=")
        .method("DELETE").asString
      response.code shouldBe NO_CONTENT

      Then("The collaborator is removed")
      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.collaborators should not contain Collaborator(emailAddress, Role.DEVELOPER)

      apiPlatformEventsStub.verifyTeamMemberRemovedEventSent()
    }
  }

  feature("Update an application") {

    scenario("Update an application") {

      Given("A third party application")
      val originalOverrides: Set[OverrideFlag] = Set(PersistLogin(), GrantWithoutConsent(Set("scope")),
        SuppressIvForAgents(Set("scope")), SuppressIvForOrganisations(Set("scope")), SuppressIvForIndividuals(Set("Scope")))
      val application = createApplication(access = standardAccess.copy(overrides = originalOverrides))

      When("I request to update the application")
      val newApplicationName = "My Renamed Application"
      val updatedRedirectUris = List("http://example.com/redirect2", "http://example.com/redirect3")
      val updatedTermsAndConditionsUrl = Some("http://example.com/terms2")
      val updatedPrivacyPolicyUrl = Some("http://example.com/privacy2")
      val updatedAccess = Standard(
        redirectUris = updatedRedirectUris,
        termsAndConditionsUrl = updatedTermsAndConditionsUrl,
        privacyPolicyUrl = updatedPrivacyPolicyUrl,
        overrides = Set.empty)
      val updatedResponse = postData(s"/application/${application.id}", applicationRequest(name = newApplicationName, access = updatedAccess))
      updatedResponse.code shouldBe OK

      Then("The application is updated but preserving the original access override flags")
      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.name shouldBe newApplicationName
      fetchedApplication.redirectUris shouldBe updatedRedirectUris
      fetchedApplication.termsAndConditionsUrl shouldBe updatedTermsAndConditionsUrl
      fetchedApplication.privacyPolicyUrl shouldBe updatedPrivacyPolicyUrl
      val fetchedAccess = fetchedApplication.access.asInstanceOf[Standard]
      fetchedAccess.redirectUris shouldBe updatedRedirectUris
      fetchedAccess.termsAndConditionsUrl shouldBe updatedTermsAndConditionsUrl
      fetchedAccess.privacyPolicyUrl shouldBe updatedPrivacyPolicyUrl
      fetchedAccess.overrides shouldBe originalOverrides
    }

    scenario("Add two client secrets then remove the last one") {
      Given("A third party application")
      val application = createApplication()
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()
      apiPlatformEventsStub.willReceiveClientRemovedEvent()
      emailStub.willPostEmailNotification()
      val createdApp = result(applicationRepository.fetch(application.id), timeout).getOrElse(fail())
      createdApp.tokens.production.clientSecrets should have size 0

      When("I request to add a production client secret")
      val fetchResponse = postData(s"/application/${application.id}/client-secret",
        s"""{"actorEmailAddress": "$emailAddress"}""")
      fetchResponse.code shouldBe OK

      Then("The client secret is added to the production environment of the application")
      val fetchResponseJson = Json.parse(fetchResponse.body).as[ApplicationTokenResponse]
      fetchResponseJson.clientSecrets should have size 1

      apiPlatformEventsStub.verifyClientSecretAddedEventSent()

      When("I request to add a second production client secret")
      val secondfetchResponse = postData(s"/application/${application.id}/client-secret",
        s"""{"actorEmailAddress": "$emailAddress"}""")
      secondfetchResponse.code shouldBe OK

      Then("The client secret is added to the production environment of the application")
      val secondFetchResponseJson = Json.parse(secondfetchResponse.body).as[ApplicationTokenResponse]
      val moreSecrets: Seq[ClientSecretResponse] = secondFetchResponseJson.clientSecrets
      moreSecrets should have size 2

      val clientSecretId = moreSecrets.last.id

      When("I request to remove a production client secret")
      val removeClientSecretResponse = postData(s"/application/${application.id}/client-secret/$clientSecretId",
        s"""{"actorEmailAddress": "$emailAddress"}""")
      removeClientSecretResponse.code shouldBe NO_CONTENT

      apiPlatformEventsStub.verifyClientSecretRemovedEventSent()
    }

    scenario("Delete an application") {
      apiSubscriptionFieldsStub.willDeleteTheSubscriptionFields()
      thirdPartyDelegatedAuthorityStub.willRevokeApplicationAuthorities()
      awsApiGatewayStub.willDeleteApplication(awsApiGatewayApplicationName)
      emailStub.willPostEmailNotification()

      Given("The gatekeeper is logged in")
      authStub.willValidateLoggedInUserHasGatekeeperRole()

      And("A third party application")
      val application = createApplication()

      When("I request to delete the application")
      val deleteResponse = postData(path = s"/application/${application.id}/delete",
        data = s"""{"gatekeeperUserId": "$gatekeeperUserId", "requestedByEmailAddress": "$emailAddress"}""",
        extraHeaders = Seq(AUTHORIZATION -> UUID.randomUUID.toString))
      deleteResponse.code shouldBe NO_CONTENT

      Then("The application is deleted")
      val fetchResponse = Http(s"$serviceUrl/application/${application.id}").asString
      fetchResponse.code shouldBe NOT_FOUND
    }

    scenario("Change rate limit tier for an application") {

      Given("The gatekeeper is logged in")
      authStub.willValidateLoggedInUserHasGatekeeperRole()

      And("A third party application with BRONZE rate limit tier exists")
      val application = createApplication()

      And("An API is available for the application")
      apiDefinitionStub.willReturnApisForApplication(application.id, Seq(anApiDefinition))

      And("AWS API Gateway is updated")
      awsApiGatewayStub.willCreateOrUpdateApplication(application.name, "", RateLimitTier.SILVER)

      Then("The response is successful")
      val response = postData(path = s"/application/${application.id}/rate-limit-tier", data = """{ "rateLimitTier" : "SILVER" }""")
      response.code shouldBe NO_CONTENT
    }
  }

  feature("Subscription") {

    scenario("Fetch API Subscriptions") {

      Given("A third party application")
      val application = createApplication()

      And("The API is available for the application")
      apiDefinitionStub.willReturnApisForApplication(application.id, Seq(anApiDefinition))

      And("The application is subscribed to an API")
      result(subscriptionExists(application.id, context, version), timeout)

      When("I fetch the API subscriptions of the application")
      val response = Http(s"$serviceUrl/application/${application.id}/subscription").asString

      Then("The API subscription is returned")
      val actualApiSubscription = Json.parse(response.body).as[Seq[ApiSubscription]]
      actualApiSubscription shouldBe
        List(ApiSubscription(apiName, serviceName, context, List(VersionSubscription(anApiDefinition.versions.head, subscribed = true))))
    }

    scenario("Fetch All API Subscriptions") {

      Given("A third party application")
      val application = createApplication("App with subscription")

      And("An API")
      apiDefinitionStub.willReturnApisForApplication(application.id, Seq(anApiDefinition))


      And("I subscribe the application to an API")
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()
      val subscribeResponse = postData(s"/application/${application.id}/subscription",
        s"""{ "context" : "$context", "version" : "$version" }""")
      And("The subscription is created")
      subscribeResponse.code shouldBe NO_CONTENT

      When("I fetch all API subscriptions")
      val response = Http(s"$serviceUrl/application/subscriptions").asString

      Then("The result includes the new subscription")
      val result = Json.parse(response.body).as[Seq[SubscriptionData]]
      result should have size 1

      val subscribedApps = result.head.applications
      subscribedApps should have size 1
      subscribedApps.head shouldBe application.id
    }

    scenario("Subscribe to an api") {

      Given("A third party application")
      val application = createApplication()
      apiPlatformEventsStub.willReceiveApiSubscribedEvent()

      And("An API")
      apiDefinitionStub.willReturnApisForApplication(application.id, Seq(anApiDefinition))

      When("I request to subscribe the application to the API")
      val subscribeResponse = postData(s"/application/${application.id}/subscription",
        s"""{ "context" : "$context", "version" : "$version" }""")

      Then("A 204 is returned")
      subscribeResponse.code shouldBe NO_CONTENT

      apiPlatformEventsStub.verifyApiSubscribedEventSent()
    }

    scenario("Unsubscribe to an api") {

      Given("A third party application")
      val application = createApplication()
      apiPlatformEventsStub.willReceiveApiUnsubscribedEvent()

      When("I request to unsubscribe the application to an API")
      val unsubscribedResponse = Http(s"$serviceUrl/application/${application.id}/subscription?context=$context&version=$version")
        .method("DELETE").asString

      Then("A 204 is returned")
      unsubscribedResponse.code shouldBe NO_CONTENT

      apiPlatformEventsStub.verifyApiUnsubscribedEventSent()
    }
  }

  feature("Uplift") {

    scenario("Request uplift for an application") {

      Given("A third party application")
      val application = createApplication()

      When("I request to uplift an application to production")
      val result = postData(s"/application/${application.id}/request-uplift",
        s"""{"requestedByEmailAddress":"admin@example.com", "applicationName": "Prod Application Name"}""")

      Then("The application is updated to PENDING_GATEKEEPER_APPROVAL")
      result.code shouldBe NO_CONTENT
      val fetchedApplication = fetchApplication(application.id)
      fetchedApplication.state.name shouldBe State.PENDING_GATEKEEPER_APPROVAL
      fetchedApplication.name shouldBe "Prod Application Name"
    }
  }

  feature("Application name validation") {
    scenario("for the invalid name 'HMRC'") {
      When("I request if a name is invalid")

      val nameToCheck = "my invalid app name HMRC"

      val requestBody = Json.obj("applicationName" -> nameToCheck).toString
      val result = postData(s"/application/name/validate", requestBody)

      Then("The response should be OK")
      result.code shouldBe OK

      Then("The response should not contain any errors")

      result.body shouldBe Json.obj("errors" -> Json.obj("invalidName" -> true, "duplicateName" -> false)).toString
    }
  }

  private def fetchApplication(id: UUID): ApplicationResponse = {
    val fetchedResponse = Http(s"$serviceUrl/application/${id.toString}").asString
    fetchedResponse.code shouldBe OK
    Json.parse(fetchedResponse.body).as[ApplicationResponse]
  }

  private def createApplication(appName: String = applicationName1, access: Access = standardAccess): ApplicationResponse = {
    awsApiGatewayStub.willCreateOrUpdateApplication(awsApiGatewayApplicationName, "", RateLimitTier.BRONZE)
    val createdResponse = postData("/application", applicationRequest(appName, access))
    createdResponse.code shouldBe CREATED
    Json.parse(createdResponse.body).as[ApplicationResponse]
  }

  private def subscriptionExists(applicationId: UUID, apiContext: String, apiVersion: String) = {
    subscriptionRepository.add(applicationId, new APIIdentifier(apiContext, apiVersion))
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

  private def applicationRequest(name: String, access: Access = standardAccess) = {
    s"""{
       |"name" : "$name",
       |"environment" : "PRODUCTION",
       |"description" : "Some Description",
       |"access" : ${Json.toJson(access)},
       |"collaborators": [
       | {
       |   "emailAddress": "admin@example.com",
       |   "role": "ADMINISTRATOR"
       | },
       | {
       |   "emailAddress": "$emailAddress",
       |   "role": "DEVELOPER"
       | }
       |]
       |}""".stripMargin.replaceAll("\n", "")
  }

}
