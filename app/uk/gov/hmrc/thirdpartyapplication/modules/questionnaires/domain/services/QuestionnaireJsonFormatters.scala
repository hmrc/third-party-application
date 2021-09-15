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

import play.api.libs.json._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import play.api.libs.functional.syntax._
import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.play.json.Union

object QuestionnaireJsonFormatters {
  implicit val jsonFormatWording = Json.valueFormat[Wording]
  implicit val jsonFormatLabel = Json.valueFormat[Label]
  implicit val jsonFormatStatementText = Json.format[StatementText]
  implicit val jsonFormatStatementLink = Json.format[StatementLink]
  
  implicit lazy val readsStatementBullets: Reads[StatementBullets] = (
      ( __ \ "bullets" ).read(Reads.seq[NonBulletStatementFragment](jsonFormatNonBulletStatementFragment)
    .map(_.toList).map(StatementBullets(_)))
  )

  implicit lazy val writesStatementBullets: OWrites[StatementBullets] = (
    (
              (__ \ "bullets").write(Writes.seq[NonBulletStatementFragment](jsonFormatNonBulletStatementFragment.writes))
    )
    .contramap (unlift(StatementBullets.unapply))
  )

  implicit lazy val jsonFormatStatementBullets: OFormat[StatementBullets] = OFormat(readsStatementBullets, writesStatementBullets)

  implicit lazy val readsCompoundFragment: Reads[CompoundFragment] = (
      ( __ \ "bullets" ).read(Reads.seq[SimpleStatementFragment](jsonFormatSimpleStatementFragment)
    .map(_.toList).map(CompoundFragment(_)))
  )

  implicit lazy val writesCompoundFragment: OWrites[CompoundFragment] = (
    (
              (__ \ "bullets").write(Writes.seq[SimpleStatementFragment](jsonFormatSimpleStatementFragment.writes))
    )
    .contramap (unlift(CompoundFragment.unapply))
  )

  implicit lazy val jsonFormatCompoundFragment: OFormat[CompoundFragment] = OFormat(readsCompoundFragment, writesCompoundFragment)

  implicit lazy val jsonFormatSimpleStatementFragment: Format[SimpleStatementFragment] = Union.from[SimpleStatementFragment]("statementType")
    .and[StatementText]("text")
    .and[StatementLink]("link")
    .format

  implicit lazy val jsonFormatNonBulletStatementFragment: Format[NonBulletStatementFragment] = Union.from[NonBulletStatementFragment]("statementType")
    .and[StatementText]("text")
    .and[StatementLink]("link")
    .andLazy[CompoundFragment]("compound", jsonFormatCompoundFragment)
    .format
  
  implicit lazy val jsonFormatStatementFragment: Format[StatementFragment] = Union.from[StatementFragment]("statementType")
    .and[StatementText]("text")
    .and[StatementLink]("link")
    .andLazy[StatementBullets]("bullets", jsonFormatStatementBullets)
    .andLazy[CompoundFragment]("compound", jsonFormatCompoundFragment)
    .format

  implicit val jsonFormatStatement = Json.format[Statement]
  implicit val jsonFormatQuestionId = Json.valueFormat[QuestionId]
  implicit val jsonFormatQuestionChoice = Json.valueFormat[QuestionChoice]
  implicit val jsonFormatTextQuestion = Json.format[TextQuestion]
  implicit val jsonFormatYesNoQuestion = Json.format[YesNoQuestion]
  implicit val jsonFormatChooseOneOfQuestion = Json.format[ChooseOneOfQuestion]
  implicit val jsonFormatMultiChoiceQuestion = Json.format[MultiChoiceQuestion]
  implicit val jsonFormatSingleChoiceQuestion = Json.format[SingleChoiceQuestion]

  implicit val jsonFormatChoiceQuestion: Format[Question] = Union.from[Question]("questionType")
    .and[ChooseOneOfQuestion]("choose")
    .and[MultiChoiceQuestion]("multi")
    .and[YesNoQuestion]("yesNo")
    .and[SingleChoiceQuestion]("single")
    .and[TextQuestion]("text")
    .format

  implicit val jsonFormatSingleChoiceAnswer = Json.format[SingleChoiceAnswer]
  implicit val jsonFormatMultipleChoiceAnswer = Json.format[MultipleChoiceAnswer]

  implicit val jsonFormatAnswerType: Format[Answer] = Union.from[Answer]("answer")
    .and[SingleChoiceAnswer]("singleChoiceAnswer")
    .and[MultipleChoiceAnswer]("multipleChoiceAnswer")
    .format

  implicit val jsonFormatAskWhenContext = Json.format[AskWhenContext]
  implicit val jsonFormatAskWhenAnswer = Json.format[AskWhenAnswer]
  implicit val jsonFormatAskAlways = Json.format[AlwaysAsk.type]

  implicit val jsonFormatCondition: Format[AskWhen] = Union.from[AskWhen]("askWhen")
    .and[AskWhenContext]("askWhenContext")
    .and[AskWhenAnswer]("askWhenAnswer")
    .and[AlwaysAsk.type]("alwaysAsk")
    .format

  implicit val jsonFormatQuestionItem = Json.format[QuestionItem]

  implicit val jsonFormatquestionnaireId = Json.valueFormat[QuestionnaireId]
  implicit val jsonFormatquestionnaire = Json.format[Questionnaire]

  implicit val jsonFormatRefenceId = Json.valueFormat[ReferenceId]
}
