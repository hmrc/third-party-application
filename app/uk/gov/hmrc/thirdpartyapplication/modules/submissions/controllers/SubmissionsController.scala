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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.controllers

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.services.SubmissionsService

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import play.api.mvc.ControllerComponents
import scala.concurrent.ExecutionContext
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import play.api.mvc.Results
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.SubmissionsFrontendJsonFormatters
import uk.gov.hmrc.thirdpartyapplication.domain.models.UserId

object SubmissionsController {

  case class ErrorMessage(message: String)
  implicit val writesErrorMessage = Json.writes[ErrorMessage]

  case class RecordAnswersRequest(answers: List[String])
  implicit val readsRecordAnswersRequest = Json.reads[RecordAnswersRequest]

  case class CreateSubmissionRequest(userId: UserId)
  implicit val readsCreateSubmissionRequest = Json.reads[CreateSubmissionRequest]
}

@Singleton
class SubmissionsController @Inject()(
  service: SubmissionsService,
  cc: ControllerComponents
)(
  implicit val ec: ExecutionContext
) 
extends BackendController(cc) with SubmissionsFrontendJsonFormatters {
  import SubmissionsController._

  def createSubmissionFor(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s))

    withJsonBody[CreateSubmissionRequest] { submissionRequest =>
      service.create(applicationId, submissionRequest.userId).map(_.fold(failed, success))
    }
  }

  def fetchSubmission(id: SubmissionId) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())

    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s))
   
    service.fetch(id).map(_.fold(failed)(success))
  }

  def fetchLatest(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())
    
    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s))

    service.fetchLatest(applicationId).map(_.fold(failed)(success))
  }

  def fetchLatestMarkedSubmission(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = (msg: String) => NotFound(Json.toJson(ErrorMessage(msg)))
    
    val success = (s: MarkedSubmission) => Ok(Json.toJson(s))

    service.fetchLatestMarkedSubmission(applicationId).map(_.fold(failed, success))
  }

  def recordAnswers(submissionId: SubmissionId, questionId: QuestionId) = Action.async(parse.json) { implicit request =>
    val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s))

    withJsonBody[RecordAnswersRequest] { answersRequest =>
      service.recordAnswers(submissionId, questionId, answersRequest.answers).map(_.fold(failed, success))
    }
  }

  def latestSubmissionIsCompleted(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())
    
    val success = (s: ExtendedSubmission) => Ok(Json.toJson(s.isCompleted))

    service.fetchLatest(applicationId).map(_.fold(failed)(success))
  }
}
