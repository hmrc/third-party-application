/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.approvals.controller

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.BeforeAndAfterEach
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.test.Helpers.CONTENT_TYPE
import uk.gov.hmrc.apiplatform.modules.approvals.utils.ServerBaseISpec
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.{QuestionnaireDAO, SubmissionsRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, FixedClock}
import java.util.UUID

class ApprovalsControllerISpec extends ServerBaseISpec with FixedClock with ApplicationTestData with SubmissionsTestData with BeforeAndAfterEach {

  protected override def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.auth.port" -> wireMockPort,
        "metrics.enabled" -> true,
        "auditing.enabled" -> false,
        "mongodb.uri" -> s"mongodb://127.0.0.1:27017/test-${this.getClass.getSimpleName}",
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort,
        "microservice.services.email.host" -> wireMockHost,
        "microservice.services.email.port" -> wireMockPort
      )

  def grantUrl(id: String) = s"http://localhost:$port/approvals/application/$id/grant"

  val wsClient: WSClient = app.injector.instanceOf[WSClient]
  val applicationRepo = app.injector.instanceOf[ApplicationRepository]
  val submissionRepo = app.injector.instanceOf[SubmissionsRepository]
  val questionaireDao = app.injector.instanceOf[QuestionnaireDAO]

  override def beforeEach() ={
    super.beforeEach()
    applicationRepo.collection.drop
    submissionRepo.collection.drop
  }


  def callPostEndpoint(url: String, body: String, headers: List[(String, String)]): WSResponse =
    wsClient
      .url(url)
      .withHttpHeaders(headers: _*)
      .withFollowRedirects(false)
      .post(body)
      .futureValue

  def stubEmail(): Unit ={
    stubFor(post(urlEqualTo("/hmrc/email"))
      .willReturn(
        aResponse()
          .withStatus(OK)
      )
    )
  }


 "ApprovalsController" should {

   def primeData(appId: ApplicationId): Unit ={

     val application = anApplicationData(appId, pendingGatekeeperApprovalState("bob"))
     await(applicationRepo.save(application))
     await(submissionRepo.collection.insertOne(submittedSubmission.copy(applicationId = appId)).toFuture())
   }
   "return 404 when application id does not exist" in {
     val bodyWontBeParsed = "{}"
     val randomAppId = UUID.randomUUID().toString
     val result = callPostEndpoint(grantUrl(randomAppId), bodyWontBeParsed, headers = List.empty)
     result.status mustBe NOT_FOUND
     result.body mustBe s"""{"code":"APPLICATION_NOT_FOUND","message":"Application $randomAppId doesn't exist"}"""
   }

   "parse date as millis from epoch" in {
     val appId: ApplicationId = ApplicationId(UUID.randomUUID())
    primeData(appId)
    stubEmail()
    val requestBody = """{"gatekeeperUserName":"Bob Hope","responsibleIndividualVerificationDate": 1651735542391}"""
     val result = callPostEndpoint(grantUrl(appId.value.toString), requestBody, headers = List(CONTENT_TYPE -> "application/json"))
     result.status mustBe OK
     val response = Json.parse(result.body).validate[ApplicationResponse].asOpt
     response must not be None

   }

   "parse date as String" in {
     val appId: ApplicationId = ApplicationId(UUID.randomUUID())
     primeData(appId)
     stubEmail()
     val requestBody = """{"gatekeeperUserName":"Bob Hope","responsibleIndividualVerificationDate":"2022-03-27T00:00:00.000Z"}"""

     val result = callPostEndpoint(grantUrl(appId.value.toString), requestBody, headers = List(CONTENT_TYPE -> "application/json"))
     result.status mustBe OK
     val response = Json.parse(result.body).validate[ApplicationResponse].asOpt
     response must not be None
   }
 }



}
