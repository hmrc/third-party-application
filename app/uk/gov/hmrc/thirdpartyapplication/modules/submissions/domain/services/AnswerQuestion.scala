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

object AnswerQuestion {
  import Submissions.AnswersToQuestions

  private def fromOption[A](opt: Option[A], msg: String): Either[String,A] = opt.fold[Either[String,A]](Left(msg))(v => Right(v))

  def recordAnswer(submission: Submission, questionId: QuestionId, rawAnswers: NonEmptyList[String], context: Context): Either[String, Submission] = {
    for {
      question                      <- fromOption(submission.findQuestion(questionId), "Not valid for this submission")
      validatedAnswers              <- validateAnswersToQuestion(question, rawAnswers)
      updatedAnswersToQuestions      = submission.answersToQuestions + (questionId -> validatedAnswers)
      updatedQuestionnaireProgress   = deriveProgressOfQuestionnaires(submission.allQuestionnaires, context, updatedAnswersToQuestions)
      updatedSubmission              = submission.copy(answersToQuestions = updatedAnswersToQuestions, questionnaireProgress = updatedQuestionnaireProgress)
    } yield updatedSubmission
  }

  // If NO next question
  //   If no answers for questionnaire then NotApplicable else Completed
  // If Next Question
  //   If no answers for questionnaire then NotStarted else InProgress
  def deriveProgressOfQuestionnaire(questionnaire: Questionnaire, context: Context, answersToQuestions: AnswersToQuestions): QuestionnaireProgress = {
    val nextQuestion = NextQuestion.getNextQuestion(questionnaire, context, answersToQuestions)
    val hasAnswersForQuestionnaire: Boolean = questionnaire.questions.map(_.question.id).exists(id => answersToQuestions.contains(id))
    
    val state = (nextQuestion, hasAnswersForQuestionnaire) match {
      case (None, true)       => Completed
      case (None, false)      => NotApplicable
      case (_, true)          => InProgress
      case (_, false)         => NotStarted
    }

    QuestionnaireProgress(state, nextQuestion.map(_.id))
  }
    
  def deriveProgressOfQuestionnaires(questionnaires: NonEmptyList[Questionnaire], context: Context, answersToQuestions: AnswersToQuestions): Map[QuestionnaireId, QuestionnaireProgress] = {
    questionnaires.toList.map(q => (q.id -> deriveProgressOfQuestionnaire(q, context, answersToQuestions))).toMap
  }

  def validateAnswersToQuestion(question: Question, rawAnswers: NonEmptyList[String]): Either[String, ActualAnswer] = {
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
