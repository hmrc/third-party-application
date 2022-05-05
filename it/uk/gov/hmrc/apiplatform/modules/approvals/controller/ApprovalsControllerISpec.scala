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

import cats.data.NonEmptyList
import org.scalatest.BeforeAndAfterEach
import play.api.http.Status.{NOT_FOUND}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.{WSClient, WSResponse}
import uk.gov.hmrc.apiplatform.modules.approvals.utils.ServerBaseISpec
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{ActualAnswer, Question, Submission}
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.{QuestionnaireDAO, SubmissionsRepository}
import uk.gov.hmrc.apiplatform.modules.submissions.services.DeriveContext
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, FixedClock}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global


class ApprovalsControllerISpec extends ServerBaseISpec with FixedClock with ApplicationTestData with BeforeAndAfterEach {


  protected override def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.auth.port" -> wireMockPort,
        "metrics.enabled" -> true,
        "auditing.enabled" -> false,
        "mongodb.uri" -> s"mongodb://127.0.0.1:27017/test-${this.getClass.getSimpleName}",
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort
      )

  def grantUrl(id: String) = s"http://localhost:$port/approvals/application/$id/grant"

  val wsClient: WSClient = app.injector.instanceOf[WSClient]
  val applicationRepo = app.injector.instanceOf[ApplicationRepository]
  val submissionRepo = app.injector.instanceOf[SubmissionsRepository]
  val questionaireDao = app.injector.instanceOf[QuestionnaireDAO]

  override def beforeEach() ={
    super.beforeEach()
    applicationRepo.drop
    submissionRepo.drop
  }


  def callPostEndpoint(url: String, body: String, headers: List[(String, String)]): WSResponse =
    wsClient
      .url(url)
      .withHttpHeaders(headers: _*)
      .withFollowRedirects(false)
      .post(body)
      .futureValue


 "ApprovalsController" should {

   "return 404 when application id does not exist" in {
     val bodyWontBeParsed = "{}"
     val randomAppId = UUID.randomUUID().toString
     val result = callPostEndpoint(grantUrl(randomAppId), bodyWontBeParsed, headers = List.empty)
     result.status mustBe NOT_FOUND
     result.body mustBe s"""{"code":"APPLICATION_NOT_FOUND","message":"Application $randomAppId doesn't exist"}"""
   }

   "return OK and application exists blah balh" in {
     val appId: ApplicationId = ApplicationId(UUID.randomUUID())
     val application = anApplicationData(appId, pendingGatekeeperApprovalState("bob"))
     await(applicationRepo.save(application))
     val emptyAnswers = Map.empty[Question.Id,ActualAnswer]
     val context = DeriveContext.deriveFor(application, List.empty)
     val newInstance           =  Submission.Instance(0, emptyAnswers, NonEmptyList.of(Submission.Status.Created(LocalDateTime.now(clock), "some person")))
     val groups = await(questionaireDao.fetchActiveGroupsOfQuestionnaires())
     val submission = Submission(Submission.Id.random, appId, LocalDateTime.now(clock), groups, QuestionnaireDAO.questionIdsOfInterest, NonEmptyList.of(newInstance), context)
     await(submissionRepo.insert(submission))
    // val requestBody = """{"gatekeeperUserName":"Rob Dawson","responsibleIndividualVerificationDate":"2022-03-27T00:00:00.000Z"}"""
     val requestBody = """{"gatekeeperUserName":"Rob Dawson"}"""
     val result = callPostEndpoint(grantUrl(appId.value.toString), requestBody, headers = List.empty)
    // result.status mustBe NOT_FOUND
     result.body mustBe s"""{"code":"APPLICATION_NOT_FOUND","message":"Application ${appId.value.toString} doesn't exist"}"""
   }
 }



}
