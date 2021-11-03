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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import cats.data.NonEmptyList
import cats.implicits._

object ValidateAnswers {
  
  def validate(question: Question, rawAnswers: Option[NonEmptyList[String]]): Either[String, ActualAnswer] = {
    (rawAnswers, question) match {
      case (Some(raw), q: NonOptionalQuestion)     => validateNonOptional(q, raw)
      case (Some(raw), OptionalQuestion(inner, _)) => validateNonOptional(inner, raw).map(a => OptionalAnswer[inner.AnswerType](Some(a.asInstanceOf[inner.AnswerType])))
      case (None, OptionalQuestion(inner, _))      => Either.right(OptionalAnswer[inner.AnswerType](None))
      case (None, _)                               => Either.left("Non-optional question must have an answer")
    }
  }

  def validateNonOptional(question: NonOptionalQuestion, rawAnswers: NonEmptyList[String]): Either[String, ActualAnswer] = {
    question match {
      case q: SingleChoiceQuestion => 
        Either.fromOption(
          rawAnswers
          .head
          .some
          .filter(answer => q.choices.contains(PossibleAnswer(answer)))
          .map(SingleChoiceAnswer(_))
          , "The answer is not valid for this question"
        )
      case q: MultiChoiceQuestion =>
        val (valid, invalid) = rawAnswers.toList.partition(answer => q.choices.contains(PossibleAnswer(answer)))
        invalid match {
          case Nil   => MultipleChoiceAnswer(valid.toSet).asRight
          case _     => "Some answers are not valid for this question".asLeft
        }
      case q: TextQuestion => 
        if(rawAnswers.head.nonEmpty) {
          TextAnswer(rawAnswers.head).asRight
        } else {
          "A text answer cannot be blank".asLeft
        }
    }
  }
}
