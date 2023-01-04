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

import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO
import play.api.libs.json.Json
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

object QuestionnairesController {
  case class ErrorMessage(message: String)
  implicit val writesErrorMessage = Json.writes[ErrorMessage]
}

@Singleton
class QuestionnairesController @Inject() (
    dao: QuestionnaireDAO,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) {
  import QuestionnairesController._
  import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.GroupOfQuestionnairesJsonFormatters._

  def activeQuestionnaires = Action.async {
    dao.fetchActiveGroupsOfQuestionnaires.map(xs => Ok(Json.toJson(xs)))
  }

  def fetch(questionnaireId: Questionnaire.Id) = Action.async { _ =>
    val failed = BadRequest(Json.toJson(ErrorMessage("No such questionnaire")))

    val success = (questionnaire: Questionnaire) => Ok(Json.toJson(questionnaire))

    dao.fetch(questionnaireId).map(_.fold(failed)(success))
  }
}
