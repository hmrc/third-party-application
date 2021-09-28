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
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.Submission.AnswerMapOfMaps
import cats.data.NonEmptyList
import cats.implicits._
import scala.collection.immutable.ListMap

object AnswerQuestion {
  
  def createMapFor(qs: List[Questionnaire]): AnswerMapOfMaps = qs.map(q => (q.id, ListMap.empty[QuestionId, ActualAnswer])).toMap

  def fromOption[A](opt: Option[A], msg: String): Either[String,A] = opt.fold[Either[String,A]](Left(msg))(v => Right(v))

  def answer(submission: Submission, questionnaire: Questionnaire, questionId: QuestionId, rawAnswers: NonEmptyList[String]): Either[String, Submission] = {
    val oQuestion = questionnaire.question(questionId)
    val answerMap = submission.questionnaireAnswers.get(questionnaire.id)

    for {
      question                      <- fromOption(oQuestion, "Not valid for questionnaire")
      _                             <- fromOption(answerMap, "Not valid for this submission")
      validatedAnswers              <- validateAnswersToQuestion(question, rawAnswers)
      updatedListMap                 = submission.questionnaireAnswers(questionnaire.id).updated(questionId, validatedAnswers)
      updatedQuestionnaireAnswers    = submission.questionnaireAnswers + (questionnaire.id -> updatedListMap)
      updatedSubmission              = submission.copy(questionnaireAnswers = updatedQuestionnaireAnswers)
    } yield updatedSubmission
  }

  def validateAnswersToQuestion(question: Question, answers: NonEmptyList[String]): Either[String, ActualAnswer] = {
    question match {
      case q: SingleChoiceQuestion => 
        Either.fromOption(
          answers
          .head
          .some
          .filter(answer => q.choices.contains(PossibleAnswer(answer)))
          .map(SingleChoiceAnswer(_))
          , "The answer is not valid for this question"
        )
      case q: MultiChoiceQuestion =>
        val (valid, invalid) = answers.toList.partition(answer => q.choices.contains(PossibleAnswer(answer)))
        invalid match {
          case Nil   => MultipleChoiceAnswer(valid.toSet).asRight
          case _     => "Some answers are not valid for this question".asLeft
        }
      case q: TextQuestion => 
        if(answers.head.nonEmpty) {
          TextAnswer(answers.head).asRight
        } else {
          "A text answer cannot be blank".asLeft
        }
    }
  }

}
