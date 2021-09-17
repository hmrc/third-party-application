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
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.services.QuestionnaireService

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import play.api.mvc.ControllerComponents
import scala.concurrent.Future
import cats.implicits._
import scala.concurrent.ExecutionContext
import play.api.mvc.Result
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

object AnswersController {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AnswersToQuestionnaireJsonFormatters._

  case class FetchResponse(questionnaire: Questionnaire, answers: AnswersToQuestionnaire)
  implicit val writesFetchReponse = Json.writes[FetchResponse]

  case class ErrorMessage(message: String)
  implicit val writesErrorMessage = Json.writes[ErrorMessage]

  case class RaiseRequest(applicationId: ApplicationId, questionnaireId: QuestionnaireId)
  implicit val readsRaiseRequest = Json.reads[RaiseRequest]
  
  case class RaiseResponse(referenceId: ReferenceId)
  implicit val writesRaiseResponse = Json.writes[RaiseResponse]
}

@Singleton
class AnswersController @Inject()(
  service: QuestionnaireService,
  cc: ControllerComponents
)(
  implicit val ec: ExecutionContext
) 
extends BackendController(cc) {
  import AnswersController._

  def fetch(referenceId: ReferenceId) = Action.async { _ =>
    val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

    val success = (tuple: ( Questionnaire, AnswersToQuestionnaire)) => Ok(Json.toJson(FetchResponse(tuple._1, tuple._2)))

    service.fetch(referenceId).map(_.fold(failed, success))
  }

  def raise() = Action.async(parse.json) { implicit request =>
    val failed = (msg: String) => BadRequest(Json.toJson(ErrorMessage(msg)))

    val success = (referenceId: ReferenceId) => Ok(Json.toJson(RaiseResponse(referenceId)))

    withJsonBody[RaiseRequest] { raiseRequest =>
      service.raiseQuestionnaire(raiseRequest.applicationId, raiseRequest.questionnaireId).map(_.fold(failed, success))
    }
  }
}
