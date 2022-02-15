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

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

object MarkAnswer {
  //
  // Assume answer is valid for question as it is only called for validated completed submissions
  // 

  protected def markSingleChoiceAnswer(question: SingleChoiceQuestion, answer: SingleChoiceAnswer): Mark =
    question.marking.get(PossibleAnswer(answer.value)).get

  protected def markMultiChoiceAnswer(question: MultiChoiceQuestion, answer: MultipleChoiceAnswer): Mark = {
    import cats.Monoid
    import Mark._

    Monoid.combineAll(
      answer.values
      .map(PossibleAnswer)
      .map(question.marking.get(_).get)
    )
    
  }

  protected def markAnswer(question: Question, answer: ActualAnswer): Mark = {

    (question, answer) match {
      case (_, NoAnswer) => question.absenceMark.get
      case (q: TextQuestion, a: TextAnswer) => Pass
      case (q: MultiChoiceQuestion, a: MultipleChoiceAnswer) => markMultiChoiceAnswer(q, a)
      case (q: SingleChoiceQuestion, a: SingleChoiceAnswer) => markSingleChoiceAnswer(q, a)
      case (q: AcknowledgementOnly, AcknowledgedAnswer) => Pass
      case _ => throw new IllegalArgumentException("Unexpectely the answer is not valid")
    }
  }

  def markSubmission(submission: Submission): Map[QuestionId, Mark] = {
    require(submission.status.isAnsweredCompletely)
    // All answers must be valid to have got here
  
    // All questions should/must exist for these questionIds.
    def unsafeGetQuestion(id: QuestionId): Question = submission.findQuestion(id).get

    submission.latestInstance.answersToQuestions.map {
      case (id: QuestionId, answer: ActualAnswer) => (id -> markAnswer(unsafeGetQuestion(id), answer))
    }
  }
}

/*
  submission => (questions, instance) => (questions, answersToQuestions)

  id -> answer =>
    id -> option(question), answer =>
      allowed noanswer?
      id -> option(question), option(mark) =>
        id -> mark

  Fails
    No question
    No matching answer
    No mark
*/
