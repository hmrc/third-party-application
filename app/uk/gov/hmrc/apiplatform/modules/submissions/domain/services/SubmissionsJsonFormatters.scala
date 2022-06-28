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

package uk.gov.hmrc.apiplatform.modules.submissions.domain.services

import play.api.libs.json._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.play.json.Union
import java.time.LocalDateTime

trait BaseSubmissionsJsonFormatters extends GroupOfQuestionnairesJsonFormatters {

  implicit val dateFormat: Format[LocalDateTime]
  implicit lazy val keyReadsQuestionnaireId: KeyReads[Questionnaire.Id]   = key => JsSuccess(Questionnaire.Id(key))
  implicit lazy val keyWritesQuestionnaireId: KeyWrites[Questionnaire.Id] = _.value

  implicit lazy val stateWrites: Writes[QuestionnaireState] = Writes {
    case QuestionnaireState.NotStarted    => JsString("NotStarted")
    case QuestionnaireState.InProgress    => JsString("InProgress")
    case QuestionnaireState.NotApplicable => JsString("NotApplicable")
    case QuestionnaireState.Completed     => JsString("Completed")
  }

  implicit lazy val stateReads: Reads[QuestionnaireState] = Reads {
    case JsString("NotStarted")    => JsSuccess(QuestionnaireState.NotStarted)
    case JsString("InProgress")    => JsSuccess(QuestionnaireState.InProgress)
    case JsString("NotApplicable") => JsSuccess(QuestionnaireState.NotApplicable)
    case JsString("Completed")     => JsSuccess(QuestionnaireState.Completed)
    case _                         => JsError("Failed to parse QuestionnaireState value")
  }

  implicit lazy val questionnaireProgressFormat = Json.format[QuestionnaireProgress]

  implicit lazy val answersToQuestionsFormat: OFormat[Map[Question.Id, Option[ActualAnswer]]] = implicitly

  implicit lazy val questionIdsOfInterestFormat = Json.format[QuestionIdsOfInterest]

  import Submission.Status._

  implicit lazy val RejectedStatusFormat             = Json.format[Declined]
  implicit lazy val AcceptedStatusFormat             = Json.format[Granted]
  implicit lazy val AcceptedWithWarningsStatusFormat = Json.format[GrantedWithWarnings]
  implicit lazy val SubmittedStatusFormat            = Json.format[Submitted]
  implicit lazy val answeringStatusFormat            = Json.format[Answering]
  implicit lazy val CreatedStatusFormat              = Json.format[Created]

  implicit lazy val submissionStatus = Union.from[Submission.Status]("Submission.StatusType")
    .and[Declined]("declined")
    .and[Granted]("granted")
    .and[GrantedWithWarnings]("grantedWithWarnings")
    .and[Submitted]("submitted")
    .and[Answering]("answering")
    .and[Created]("created")
    .format

  implicit lazy val submissionInstanceFormat = Json.format[Submission.Instance]
  implicit lazy val submissionFormat         = Json.format[Submission]
}

object SubmissionsJsonFormatters extends BaseSubmissionsJsonFormatters {
  implicit val dateFormat = MongoJavatimeFormats.localDateTimeFormat
}

trait SubmissionsFrontendJsonFormatters extends BaseSubmissionsJsonFormatters with EnvReads with EnvWrites {

  implicit val utcReads = DefaultLocalDateTimeReads
  implicit val utcWrites = DefaultLocalDateTimeWrites
  implicit val dateFormat = Format(utcReads, utcWrites)

  implicit lazy val extendedSubmissionFormat = Json.format[ExtendedSubmission]
  implicit lazy val markedSubmissionFormat   = Json.format[MarkedSubmission]
}

object SubmissionsFrontendJsonFormatters extends SubmissionsFrontendJsonFormatters
