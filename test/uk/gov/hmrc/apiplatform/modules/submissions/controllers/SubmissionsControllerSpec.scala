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

package uk.gov.hmrc.apiplatform.modules.submissions.controllers

import scala.concurrent.ExecutionContext.Implicits.global

import akka.stream.testkit.NoMaterializer

import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{ExtendedSubmission, MarkedSubmission, Submission}
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class SubmissionsControllerSpec extends AsyncHmrcSpec {
  import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionsFrontendJsonFormatters._
  implicit val mat = NoMaterializer

  implicit val readsExtendedSubmission = Json.reads[Submission]

  trait Setup extends SubmissionsServiceMockModule with SubmissionsTestData {
    val underTest = new SubmissionsController(SubmissionsServiceMock.aMock, Helpers.stubControllerComponents())
  }

  "create new submission" should {
    implicit val writer = Json.writes[SubmissionsController.CreateSubmissionRequest]
    val fakeRequest     = FakeRequest(POST, "/").withBody(Json.toJson(SubmissionsController.CreateSubmissionRequest("bob@example.com")))

    "return an ok response" in new Setup {
      SubmissionsServiceMock.Create.thenReturn(aSubmission)

      val result = underTest.createSubmissionFor(applicationId)(fakeRequest)

      status(result) shouldBe OK

      contentAsJson(result).validate[Submission] match {
        case JsSuccess(submission, _) =>
          submission shouldBe aSubmission
        case JsError(f)               => fail(s"Not parsed as a response $f")
      }
    }

    "return a bad request response" in new Setup {
      SubmissionsServiceMock.Create.thenFails("Test Error")

      val result = underTest.createSubmissionFor(applicationId)(fakeRequest)

      status(result) shouldBe BAD_REQUEST
    }
  }

  "fetchLatest" should {

    "return ok response with submission when found" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(aSubmission)

      val result = underTest.fetchLatest(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[Submission] match {
        case JsSuccess(_, _) => succeed
        case JsError(e)      => fail(s"Not parsed as a response $e")
      }
    }

    "return not found when not found" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone

      val result = underTest.fetchLatest(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe NOT_FOUND
    }
  }
  "fetchLatestExtended" should {

    "return ok response with submission when found" in new Setup {
      SubmissionsServiceMock.FetchLatestExtended.thenReturn(aSubmission.withNotStartedProgresss)

      val result = underTest.fetchLatestExtended(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[ExtendedSubmission] match {
        case JsSuccess(extendedSubmission, _) => succeed
        case JsError(e)                       => fail(s"Not parsed as a response $e")
      }
    }

    "return not found when not found" in new Setup {
      SubmissionsServiceMock.FetchLatestExtended.thenReturnNone

      val result = underTest.fetchLatestExtended(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe NOT_FOUND
    }
  }

  "fetchSubmission" should {

    "return ok response with submission when found" in new Setup {
      SubmissionsServiceMock.Fetch.thenReturn(aSubmission.withNotStartedProgresss)

      val result = underTest.fetchSubmission(submissionId)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[ExtendedSubmission] match {
        case JsSuccess(extendedSubmission, _) => succeed
        case JsError(e)                       => fail(s"Not parsed as a response $e")
      }
    }

    "return not found when not found" in new Setup {
      SubmissionsServiceMock.Fetch.thenReturnNone

      val result = underTest.fetchSubmission(submissionId)(FakeRequest(GET, "/"))

      status(result) shouldBe NOT_FOUND
    }
  }

  "fetchLatestMarkedSubmission" should {
    "return ok response with submission when found" in new Setup {
      val markedSubmission = MarkedSubmission(aSubmission, Map.empty)
      SubmissionsServiceMock.FetchLatestMarkedSubmission.thenReturn(markedSubmission)

      val result = underTest.fetchLatestMarkedSubmission(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[MarkedSubmission] match {
        case JsSuccess(markedSubmission, _) => succeed
        case JsError(e)                     => fail(s"Not parsed as a response $e")
      }
    }

    "return not found when not found" in new Setup {
      SubmissionsServiceMock.FetchLatestMarkedSubmission.thenFails("nope")

      val result = underTest.fetchLatestMarkedSubmission(applicationId)(FakeRequest(GET, "/"))

      status(result) shouldBe NOT_FOUND
    }
  }

  "recordAnswers" should {
    "return an OK response" in new Setup {
      implicit val writes = Json.writes[SubmissionsController.RecordAnswersRequest]

      SubmissionsServiceMock.RecordAnswers.thenReturn(ExtendedSubmission(answeringSubmission, answeringSubmission.withIncompleteProgress().questionnaireProgress))

      val answerJsonBody = Json.toJson(SubmissionsController.RecordAnswersRequest(List("Yes")))

      val result = underTest.recordAnswers(submissionId, questionId)(FakeRequest(PUT, "/").withBody(answerJsonBody))

      status(result) shouldBe OK
    }

    "return an bad request response when something goes wrong" in new Setup {
      implicit val writes = Json.writes[SubmissionsController.RecordAnswersRequest]

      SubmissionsServiceMock.RecordAnswers.thenFails("bang")

      val answerJsonBody = Json.toJson(SubmissionsController.RecordAnswersRequest(List("Yes")))
      val result         = underTest.recordAnswers(submissionId, questionId)(FakeRequest(PUT, "/").withBody(answerJsonBody))

      status(result) shouldBe BAD_REQUEST
    }
  }
}
