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

import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import play.api.libs.json._
import org.joda.time.DateTimeZone

trait SubmissionsJsonFormatters extends GroupOfQuestionnairesJsonFormatters {
  
  implicit val keyReadsQuestionId: KeyReads[QuestionId] = key => JsSuccess(QuestionId(key))
  implicit val keyWritesQuestionId: KeyWrites[QuestionId] = _.value

  implicit val keyReadsQuestionnaireId: KeyReads[QuestionnaireId] = key => JsSuccess(QuestionnaireId(key))
  implicit val keyWritesQuestionnaireId: KeyWrites[QuestionnaireId] = _.value


}

object SubmissionsJsonFormatters extends SubmissionsJsonFormatters {
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val submissionFormat = Json.format[Submission]
  implicit val extendedSubmissionFormat = Json.format[ExtendedSubmission]
}

object SubmissionsFrontendJsonFormatters extends SubmissionsJsonFormatters {
  import JodaWrites.JodaDateTimeWrites
  implicit val utcReads = JodaReads.DefaultJodaDateTimeReads.map(dt => dt.withZone(DateTimeZone.UTC))
  implicit val submissionFormat = Json.format[Submission]
  implicit val extendedSubmissionFormat = Json.format[ExtendedSubmission]
}
