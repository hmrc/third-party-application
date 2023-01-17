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

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, Results}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionsFrontendJsonFormatters
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

object SubmissionsController {

  case class ErrorMessage(message: String)
  implicit val writesErrorMessage = Json.writes[ErrorMessage]

  case class RecordAnswersRequest(answers: List[String])
  implicit val readsRecordAnswersRequest = Json.reads[RecordAnswersRequest]

  case class CreateSubmissionRequest(requestedBy: String)
  implicit val readsCreateSubmissionRequest = Json.reads[CreateSubmissionRequest]
}

@Singleton
class SubmissionsController @Inject() (
    service: SubmissionsService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) with SubmissionsFrontendJsonFormatters {
  import SubmissionsController._

  def createSubmissionFor(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

    val success = (s: Submission) => Ok(Json.toJson(s))

    withJsonBody[CreateSubmissionRequest] { submissionRequest =>
      service.create(applicationId, submissionRequest.requestedBy).map(_.fold(failed, success))
    }
  }

  def fetchSubmission(id: Submission.Id) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())

    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s))

    service.fetch(id).map(_.fold(failed)(success))
  }

  def fetchLatest(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())

    val success = (s: Submission) => Ok(Json.toJson(s))

    service.fetchLatest(applicationId).map(_.fold(failed)(success))
  }

  def fetchLatestExtended(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())

    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s))

    service.fetchLatestExtended(applicationId).map(_.fold(failed)(success))
  }

  def fetchLatestMarkedSubmission(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = (msg: String) => NotFound(Json.toJson(ErrorMessage(msg)))

    val success = (s: MarkedSubmission) => Ok(Json.toJson(s))

    service.fetchLatestMarkedSubmission(applicationId).map(_.fold(failed, success))
  }

  def recordAnswers(submissionId: Submission.Id, questionId: Question.Id) = Action.async(parse.json) { implicit request =>
    val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s))

    withJsonBody[RecordAnswersRequest] { answersRequest =>
      service.recordAnswers(submissionId, questionId, answersRequest.answers).map(_.fold(failed, success))
    }
  }

  def latestSubmissionIsCompleted(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())

    val success = (s: Submission) => Ok(Json.toJson(s.status.isAnsweredCompletely))

    service.fetchLatest(applicationId).map(_.fold(failed)(success))
  }
}
