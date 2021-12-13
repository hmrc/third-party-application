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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models

import scala.collection.immutable.ListSet
import scala.collection.immutable.ListMap

case class Wording(value: String) extends AnyVal

case class QuestionId(value: String) extends AnyVal

object QuestionId {
  def random = QuestionId(java.util.UUID.randomUUID.toString)

  implicit val jsonFormatQuestionId = play.api.libs.json.Json.valueFormat[QuestionId]
}

sealed trait Question {
  def id: QuestionId
  def wording: Wording
  def statement: Statement

  def absence: Option[(String, MarkAnswer)]

  def absenceText: Option[String] = absence.map(_._1)
  def absenceMark: Option[MarkAnswer] = absence.map(_._2)

  final def isOptional: Boolean = absence.isDefined
}

case class TextQuestion(id: QuestionId, wording: Wording, statement: Statement, absence: Option[(String, MarkAnswer)] = None) extends Question

case class AcknowledgementOnly(id: QuestionId, wording: Wording, statement: Statement) extends Question {
  val absence = None
}

sealed trait MarkAnswer
case object Fail extends MarkAnswer
case object Warn extends MarkAnswer
case object Pass extends MarkAnswer
case object ChangeMe extends MarkAnswer

case class PossibleAnswer(value: String) extends AnyVal

sealed trait ChoiceQuestion extends Question {
  def choices: ListSet[PossibleAnswer]
  def marking: ListMap[PossibleAnswer, MarkAnswer]
}

sealed trait SingleChoiceQuestion extends ChoiceQuestion

case class MultiChoiceQuestion(id: QuestionId, wording: Wording, statement: Statement, marking: ListMap[PossibleAnswer, MarkAnswer], absence: Option[(String, MarkAnswer)] = None) extends ChoiceQuestion {
  lazy val choices: ListSet[PossibleAnswer] = ListSet(marking.keys.toList : _*)
}

case class ChooseOneOfQuestion(id: QuestionId, wording: Wording, statement: Statement, marking: ListMap[PossibleAnswer, MarkAnswer], absence: Option[(String, MarkAnswer)] = None) extends SingleChoiceQuestion {
  lazy val choices: ListSet[PossibleAnswer] = ListSet(marking.keys.toList : _*)
}

case class YesNoQuestion(id: QuestionId, wording: Wording, statement: Statement,  yesMarking: MarkAnswer, noMarking: MarkAnswer, absence: Option[(String, MarkAnswer)] = None) extends SingleChoiceQuestion {
  val YES = PossibleAnswer("Yes")
  val NO = PossibleAnswer("No")

  lazy val marking: ListMap[PossibleAnswer, MarkAnswer] = ListMap(YES -> yesMarking, NO -> noMarking)
  lazy val choices = ListSet(YES, NO)
}

