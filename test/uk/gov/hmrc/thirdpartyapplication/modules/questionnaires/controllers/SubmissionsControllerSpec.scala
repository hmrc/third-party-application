/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.mocks.SubmissionsServiceMockModule
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.test.Helpers
import play.api.test.Helpers._
import play.api.test.FakeRequest
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import akka.stream.testkit.NoMaterializer
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.controllers.SubmissionsController
import play.api.libs.json.JsError
import uk.gov.hmrc.thirdpartyapplication.util.TestData

class SubmissionsControllerSpec extends AsyncHmrcSpec {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.SubmissionsFrontendJsonFormatters._
  implicit val mat = NoMaterializer
  
  trait Setup extends SubmissionsServiceMockModule with TestData {
    val underTest = new SubmissionsController(SubmissionsServiceMock.aMock, Helpers.stubControllerComponents())
  }
  
  "create new submission" should {
    implicit val readsCreateNewSubmissionResponse = Json.reads[SubmissionsController.CreateNewSubmissionResponse]
    
    "return an ok response" in new Setup {

      SubmissionsServiceMock.Create.thenReturn(submission)
      
      val result = underTest.createSubmissionFor(applicationId).apply(FakeRequest(POST, "/"))

      status(result) shouldBe OK

      println(contentAsString(result))
      contentAsJson(result).validate[SubmissionsController.CreateNewSubmissionResponse] match {
        case JsSuccess(x,_) =>
          x.submission shouldBe submission
        case JsError(f) => fail(s"Not parsed as a response $f")        
      }
    }

    "return a bad request response" in new Setup {
      SubmissionsServiceMock.Create.thenFails("Test Error")
      
     val result = underTest.createSubmissionFor(applicationId).apply(FakeRequest(POST, "/"))

      status(result) shouldBe BAD_REQUEST
    }
  }

  "fetchLatest" should {
    implicit val readsResponse = Json.reads[SubmissionsController.FetchSubmissionResponse]

    "return ok response with submission when found" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(Some(submission))

      val result = underTest.fetchLatest(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[SubmissionsController.FetchSubmissionResponse] match {
        case JsSuccess(s, _) => succeed
        case JsError(e) => fail(s"Not parsed as a response $e")
      }
    }

    "return not found when not found" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(None)

      val result = underTest.fetchLatest(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe NOT_FOUND
    }
  }

  "recordAnswers" should {
    "return an OK response" in new Setup {
      import uk.gov.hmrc.thirdpartyapplication.domain.services.NonEmptyListFormatters._
      implicit val writes = Json.writes[SubmissionsController.RecordAnswersRequest]
      
      SubmissionsServiceMock.RecordAnswers.thenReturn(submission)

      val jsonBody = Json.toJson(SubmissionsController.RecordAnswersRequest(NonEmptyList.of("Yes")))
      val result = underTest.recordAnswers(submissionId, questionnaire.id, questionId)(FakeRequest(PUT, "/").withBody(jsonBody))

      status(result) shouldBe OK
    }

    "return an bad request response when something goes wrong" in new Setup {
      import uk.gov.hmrc.thirdpartyapplication.domain.services.NonEmptyListFormatters._
      implicit val writes = Json.writes[SubmissionsController.RecordAnswersRequest]
      
      SubmissionsServiceMock.RecordAnswers.thenFails("bang")

      val jsonBody = Json.toJson(SubmissionsController.RecordAnswersRequest(NonEmptyList.of("Yes")))
      val result = underTest.recordAnswers(submissionId, questionnaire.id, questionId)(FakeRequest(PUT, "/").withBody(jsonBody))

      status(result) shouldBe BAD_REQUEST
    }
  }

  "getNextQuestion" should {
    implicit val readsResponse = Json.reads[SubmissionsController.NextQuestionResponse]

     "return ok response for submission when found" in new Setup {
      SubmissionsServiceMock.GetNextQuestion.thenReturn(question)

      val result = underTest.getNextQuestion(submission.id, questionnaire.id)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[SubmissionsController.NextQuestionResponse] match {
        case JsSuccess(s, _) => s.question.value shouldBe question
        case JsError(e) => fail(s"Not parsed as a response $e")
      }
    }

    "return ok response when no next question found" in new Setup {
      SubmissionsServiceMock.GetNextQuestion.thenReturnNone()

      val result = underTest.getNextQuestion(submission.id, questionnaire.id)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[SubmissionsController.NextQuestionResponse] match {
        case JsSuccess(s, _) => s.question shouldBe None
        case JsError(e) => fail(s"Not parsed as a response $e")
      }
    }

    "return bad request response when failed" in new Setup {
      SubmissionsServiceMock.GetNextQuestion.thenFail("bang")

      val result = underTest.getNextQuestion(submission.id, questionnaire.id)(FakeRequest(GET, "/"))

      status(result) shouldBe BAD_REQUEST
    }     
  }
}
