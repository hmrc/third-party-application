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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models

import scala.collection.immutable.ListSet

case class Statement(fragments: List[StatementFragment])

object Statement {
  def apply(fragments: StatementFragment*) = new Statement(fragments.toList)
}

sealed trait StatementFragment
sealed trait NonBulletStatementFragment extends StatementFragment
sealed trait SimpleStatementFragment extends NonBulletStatementFragment
case class StatementText(text: String) extends SimpleStatementFragment
case class StatementLink(text: String, url: String) extends SimpleStatementFragment

case class StatementBullets(bullets: List[NonBulletStatementFragment]) extends StatementFragment

object StatementBullets {
  def apply(bullets: NonBulletStatementFragment*) = new StatementBullets(bullets.toList)
}

case class CompoundFragment(fragments: List[SimpleStatementFragment]) extends NonBulletStatementFragment

object CompoundFragment {
  def apply(fragments: SimpleStatementFragment*) = new CompoundFragment(fragments.toList)
}

case class Wording(value: String) extends AnyVal

case class QuestionId(value: String) extends AnyVal

object QuestionId {
  def random = QuestionId(java.util.UUID.randomUUID.toString)
}

sealed trait Question {
  def id: QuestionId
  def wording: Wording
  def statement: Statement
}

case class TextQuestion(id: QuestionId, wording: Wording, statement: Statement) extends Question

sealed trait ChoiceQuestion extends Question {
  def choices: ListSet[QuestionChoice]
}

sealed trait SingleChoiceQuestion extends ChoiceQuestion
case class MultiChoiceQuestion(id: QuestionId, wording: Wording, statement: Statement, choices: ListSet[QuestionChoice]) extends ChoiceQuestion
case class ChooseOneOfQuestion(id: QuestionId, wording: Wording, statement: Statement, choices: ListSet[QuestionChoice]) extends SingleChoiceQuestion
case class YesNoQuestion(id: QuestionId, wording: Wording, statement: Statement) extends SingleChoiceQuestion {
  lazy val choices = ListSet(QuestionChoice("Yes"), QuestionChoice("No"))
}

case class QuestionChoice(value: String) extends AnyVal