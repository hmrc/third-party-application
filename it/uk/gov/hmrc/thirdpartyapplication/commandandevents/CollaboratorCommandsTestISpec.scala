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

package uk.gov.hmrc.thirdpartyapplication.commandandevents

import org.scalatest.BeforeAndAfterEach
import play.api.Application
import play.api.http.Status.{BAD_REQUEST, CREATED, OK}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.bind
import play.api.libs.json.Json
import scalaj.http.{Http, HttpResponse}
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborators.Developer
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.RateLimitTier
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.{AddCollaborator, RemoveCollaborator}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.DispatchRequest
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors.AppCollaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.component.{BaseFeatureSpec, DummyCredentialGenerator}
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.{Access, Standard}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, CredentialGenerator, JavaDateTimeTestUtils, MetricsHelper}

import scala.concurrent.Await.ready
class CollaboratorCommandsTestISpec extends BaseFeatureSpec
with ApplicationTestData
with JavaDateTimeTestUtils
with ApplicationStateUtil
with BeforeAndAfterEach
with MetricsHelper
with FixedClock {



  val configOverrides = Map[String, Any](
    "microservice.services.api-subscription-fields.port" -> 19650,
    "microservice.services.api-platform-events.port" -> 16700,
    "microservice.services.api-gateway-stub.port" -> 19607,
    "microservice.services.auth.port" -> 18500,
    "microservice.services.email.port" -> 18300,
    "microservice.services.third-party-delegated-authority.port" -> 19606,
    "microservice.services.totp.port" -> 19988,
    "mongodb.uri"                                                -> "mongodb://localhost:27017/third-party-application-command-events-test"
  )

  override def fakeApplication(): Application = {
    GuiceApplicationBuilder()
      .configure(configOverrides + ("metrics.jvm" -> false))
      .overrides(bind[CredentialGenerator].to[DummyCredentialGenerator])
      .disable(classOf[SchedulerModule])
      .build()
  }
  lazy val applicationRepository  = app.injector.instanceOf[ApplicationRepository]


  val userId = UserId.random
  val adminUserId = UserId.random

  val awsApiGatewayApplicationName = "a" * 10
  val applicationName1             = "My 1st Application"
  val standardAccess = Standard(
    redirectUris = List("http://example.com/redirect"),
    termsAndConditionsUrl = Some("http://example.com/terms"),
    privacyPolicyUrl = Some("http://example.com/privacy"),
    overrides = Set.empty
  )
  private def dispatchCommand( data: String, applicationId: ApplicationId, extraHeaders: Seq[(String, String)] = Seq()): HttpResponse[String] = {
    val connTimeoutMs = 5000
    val readTimeoutMs = 10000
    Http(s"$serviceUrl/application/$applicationId/dispatch").postData(data).method("PATCH")
      .header("Content-Type", "application/json")
      .headers(extraHeaders)
      .timeout(connTimeoutMs, readTimeoutMs)
      .asString
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

  private def emptyApplicationRepository() = {
    ready(applicationRepository.collection.drop().toFuture(), timeout)
  }

  private def createApplication(appName: String = applicationName1, access: Access = standardAccess): ApplicationResponse = {
    awsApiGatewayStub.willCreateOrUpdateApplication(awsApiGatewayApplicationName, "", RateLimitTier.BRONZE)
    val createdResponse = postData("/application", applicationRequest(appName, access))
    createdResponse.code shouldBe CREATED
    Json.parse(createdResponse.body).as[ApplicationResponse]
  }


  Feature("AddCollaborator command is sent") {
    Scenario("for the invalid name 'HMRC'") {

      Given("No applications exist")
      emptyApplicationRepository()
      apiPlatformEventsStub.willReceiveEventType("COLLABORATOR_ADDED")
      emailStub.willPostEmailNotification()

      Given("A third party application")
      val application = createApplication()

      When("send an AddCollaborator command")
      val newCollaborator = Developer(UserId.random, LaxEmailAddress("newuser@here"))
      val cmd = AddCollaborator(AppCollaborator(devEmail), newCollaborator, now)

      val dispatchRequest = DispatchRequest(cmd, verifiedCollaboratorsToNotify = application.collaborators.map(_.emailAddress))
      val result = dispatchCommand( Json.toJson(dispatchRequest).toString, application.id)

      Then("The response should be OK")
         result.code shouldBe OK

      Given("We fetch the application by its ID")
      val fetchResponse = Http(s"$serviceUrl/application/${application.id.value}").asString
      fetchResponse.code shouldBe OK

      val appResponse = Json.parse(fetchResponse.body).as[ApplicationResponse]

      Then("the collaborator should have been added to the application")
      //check new collaborator exists on the application
      appResponse.collaborators should contain (newCollaborator)

      Then("The Collaborator Added Event was triggered")
      apiPlatformEventsStub.verifyEventWasSent("COLLABORATOR_ADDED")


    }
  }

  Feature("RemoveCollaborator command is sent") {
    Scenario("for the invalid name 'HMRC'") {

      Given("No applications exist")
      emptyApplicationRepository()
      apiPlatformEventsStub.willReceiveEventType("COLLABORATOR_REMOVED")
      emailStub.willPostEmailNotification()

      Given("A third party application")
      val application = createApplication()

      When("send a RemoveCollaborator command")
      val existingCollaborator = application.collaborators.filter(_.isDeveloper).head

      val cmd = RemoveCollaborator(AppCollaborator(anAdminEmail), existingCollaborator, now)

      val dispatchRequest = DispatchRequest(cmd, verifiedCollaboratorsToNotify = application.collaborators.map(_.emailAddress))
      val result = dispatchCommand(Json.toJson(dispatchRequest).toString, application.id)

      Then("The response should be OK")
      result.code shouldBe OK


      Given("We fetch the application by its ID")
      val fetchResponse = Http(s"$serviceUrl/application/${application.id.value}").asString
      fetchResponse.code shouldBe OK

      val appResponse = Json.parse(fetchResponse.body).as[ApplicationResponse]

      Then("the collaborator should have been removed to the application")
      appResponse.collaborators should not contain(existingCollaborator)

      Then("The Collaborator Removed Event was triggered")
      apiPlatformEventsStub.verifyEventWasSent("COLLABORATOR_REMOVED")


    }
  }
  private def applicationRequest(name: String, access: Access) = {
    s"""{
       |"name" : "$name",
       |"environment" : "PRODUCTION",
       |"description" : "Some Description",
       |"access" : ${Json.toJson(access)},
       |"collaborators": [
       | {
       |   "emailAddress": "${anAdminEmail.text}",
       |   "role": "ADMINISTRATOR",
       |   "userId": "$adminUserId"
       | },
       | {
       |   "emailAddress": "${devEmail.text}",
       |   "role": "DEVELOPER",
       |   "userId": "$userId"
       | }
       |]
       |}""".stripMargin.replaceAll("\n", "")
  }
}
