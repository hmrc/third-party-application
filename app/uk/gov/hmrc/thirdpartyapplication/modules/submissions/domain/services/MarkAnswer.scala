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

object MarkAnswer {
  // Assume answer is valid for question
  def markSingleChoiceAnswer(question: SingleChoiceQuestion, answer: SingleChoiceAnswer): Option[MarkAnswer] =
    question.marking.get(PossibleAnswer(answer.value))

  def markMultiChoiceAnswer(question: MultiChoiceQuestion, answer: MultipleChoiceAnswer): Option[MarkAnswer] = {
    answer.values
    .map(PossibleAnswer)
    .map(question.marking.get)
    .toList
    .foldRight[Option[MarkAnswer]](Some(Pass))( (a,m) => (a,m) match {
      case (None, _)       => None
      case (_, None)       => None
      case (Some(Fail), _) => Some(Fail)
      case (_, Some(Fail)) => Some(Fail)
      case (_, Some(Warn)) => Some(Warn)
    })
  }

  def markAnswer(question: Question, answer: ActualAnswer): Option[MarkAnswer] = {

    (question, answer) match {
      case (_, NoAnswer) => question.absenceMark
      case (q: TextQuestion, a: TextAnswer) => Some(Pass)
      case (q: MultiChoiceQuestion, a: MultipleChoiceAnswer) => markMultiChoiceAnswer(q, a)
      case (q: SingleChoiceQuestion, a: SingleChoiceAnswer) => markSingleChoiceAnswer(q, a)
      case (q: AcknowledgementOnly, AcknowledgedAnswer) => Some(Pass)
      case _ => None
    }
  }
}
