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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.controllers

import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.services.SubmissionsService

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import play.api.mvc.ControllerComponents
import scala.concurrent.ExecutionContext
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.domain.services.NonEmptyListFormatters._
import play.api.mvc.Results

object SubmissionsController {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.SubmissionsFrontendJsonFormatters._

  case class ErrorMessage(message: String)
  implicit val writesErrorMessage = Json.writes[ErrorMessage]

  case class CreateNewSubmissionResponse(submission: Submission)
  implicit val writesCreateNewSubmissionResponse = Json.writes[CreateNewSubmissionResponse]

  case class FetchSubmissionResponse(submission: Submission)
  implicit val writesFetchSubmissionResponse = Json.writes[FetchSubmissionResponse]

  // case class RaiseRequest(applicationId: ApplicationId, questionnaireId: QuestionnaireId)
  // implicit val readsRaiseRequest = Json.reads[RaiseRequest]
  
  // case class RecordAnswersRequest(answer: NonEmptyList[String])
  // implicit val readsRecordAnswersRequest = Json.reads[RecordAnswersRequest]

  // case class RecordAnswersResponse(referenceId: ReferenceId)
  // implicit val writesRecordAnswersResponse = Json.writes[RecordAnswersResponse]
}

@Singleton
class SubmissionsController @Inject()(
  service: SubmissionsService,
  cc: ControllerComponents
)(
  implicit val ec: ExecutionContext
) 
extends BackendController(cc) {
  import SubmissionsController._

  def create(applicationId: ApplicationId) = Action.async { _ =>
    val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

    val success = (submission: Submission) => Ok(Json.toJson(CreateNewSubmissionResponse(submission)))

    service.create(applicationId).map(_.fold(failed, success))
  }

  def fetchLatest(applicationId: ApplicationId) = Action.async { _ =>
    lazy val failed = NotFound(Results.EmptyContent())
    
    val success = (s: Submission) => Ok(Json.toJson(FetchSubmissionResponse(s)))

    service.fetchLatest(applicationId).map(_.fold(failed)(success))
  }

  // def recordAnswer(referenceId: ReferenceId, questionId: QuestionId) = Action.async(parse.json) { implicit request =>
  //   val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

  //   val success = (atq: AnswersToQuestionnaire) => Ok(Json.toJson(RecordAnswersResponse(atq.referenceId)))

  //   withJsonBody[RecordAnswersRequest] { answersRequest =>
  //     service.recordAnswer(referenceId, questionId, answersRequest.answer).map(_.fold(failed, success))
  //   }
  // }
}
