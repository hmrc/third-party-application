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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services

import play.api.libs.json.Json

import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._

trait GroupOfQuestionnairesJsonFormatters extends QuestionnaireJsonFormatters {
  implicit val groupOfQuestionnairesJsonFormat = Json.format[GroupOfQuestionnaires]
}

object GroupOfQuestionnairesJsonFormatters extends GroupOfQuestionnairesJsonFormatters

trait GroupOfQuestionnaireIdsJsonFormatters extends QuestionnaireJsonFormatters {
  implicit val groupOfQuestionnaireIdsJsonFormat = Json.format[GroupOfQuestionnaireIds]
}

object GroupOfQuestionnaireIdsJsonFormatters extends GroupOfQuestionnaireIdsJsonFormatters