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

package uk.gov.hmrc.apiplatform.modules.submissions.domain.services

import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

object ValidateAnswers {

  def validate(question: Question, rawAnswers: List[String]): Either[String, ActualAnswer] = {
    (question, rawAnswers) match {
      case (_: Question.AcknowledgementOnly, Nil) => Either.right(ActualAnswer.AcknowledgedAnswer)
      case (_: Question.AcknowledgementOnly, _)   => Either.left("Acknowledgement cannot accept answers")

      case (_, Nil) if (question.isOptional) => Either.right(ActualAnswer.NoAnswer)
      case (_, Nil)                          => Either.left("Question requires an answer")

      case (q: Question.MultiChoiceQuestion, answers) => validateAgainstPossibleAnswers(q, answers.toSet)
      case (_, a :: b :: Nil)                         => Either.left("Question only accepts one answer")

      case (q: Question.TextQuestion, head :: Nil)         => validateAgainstPossibleTextValidationRule(q, head)
      case (q: Question.SingleChoiceQuestion, head :: Nil) => validateAgainstPossibleAnswers(q, head)

    }
  }

  def validateAgainstPossibleTextValidationRule(question: Question.TextQuestion, rawAnswer: String): Either[String, ActualAnswer] = {
    question.validation
      .fold(rawAnswer.asRight[String])(v => v.validate(rawAnswer))
      .map(ActualAnswer.TextAnswer(_))
  }

  def validateAgainstPossibleAnswers(question: Question.MultiChoiceQuestion, rawAnswers: Set[String]): Either[String, ActualAnswer] = {
    if (rawAnswers subsetOf question.choices.map(_.value)) {
      Either.right(ActualAnswer.MultipleChoiceAnswer(rawAnswers))
    } else {
      Either.left("Not all answers are valid")
    }
  }

  def validateAgainstPossibleAnswers(question: Question.SingleChoiceQuestion, rawAnswer: String): Either[String, ActualAnswer] = {
    if (question.choices.map(_.value).contains(rawAnswer)) {
      Either.right(ActualAnswer.SingleChoiceAnswer(rawAnswer))
    } else {
      Either.left("Answer is not valid")
    }
  }
}
