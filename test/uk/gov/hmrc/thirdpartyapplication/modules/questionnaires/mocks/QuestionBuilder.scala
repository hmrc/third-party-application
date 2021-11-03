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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import scala.collection.immutable.ListSet

trait QuestionBuilder {
  implicit class QuestionSyntax[Q <: NonOptionalQuestion](question: Q) {
    def makeOptional: OptionalQuestion[Q] = OptionalQuestion(question, "Some Text")
  }

  def makeOptional[Q <: NonOptionalQuestion](inner: Q): OptionalQuestion[Q] = {
    OptionalQuestion(inner, "Some text")
  } 
  
  def yesNoQuestion(counter: Int): SingleChoiceQuestion = {
    YesNoQuestion(
      QuestionId.random,
      Wording(s"Wording$counter"),
      Statement(List())
    )
  }
  
  def multichoiceQuestion(counter: Int, choices: String*): MultiChoiceQuestion = {
    MultiChoiceQuestion(
      QuestionId.random,
      Wording(s"Wording$counter"),
      Statement(List()),
      ListSet(choices.map(c => PossibleAnswer(c)): _*)
    )
  }

  def textQuestion(counter: Int): TextQuestion = {
    TextQuestion(
      QuestionId.random,
      Wording(s"Wording$counter"),
      Statement(List())
    )
  }
}
