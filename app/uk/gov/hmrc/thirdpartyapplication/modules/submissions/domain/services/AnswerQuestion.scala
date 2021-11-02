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

  def questionsToAsk(questionnaire: Questionnaire, context: Context, answersToQuestions: AnswersToQuestions): List[QuestionId] = {
    questionnaire.questions.collect {
      case (qi) if AskWhen.shouldAsk(context, answersToQuestions)(qi.askWhen) => qi.question.id
    }
  }

  def recordAnswer(submission: Submission, questionId: QuestionId, rawAnswers: Option[NonEmptyList[String]], context: Context): Either[String, ExtendedSubmission] = {
    for {
      question                        <- fromOption(submission.findQuestion(questionId), "Not valid for this submission")
      validatedAnswers                <- validateAnswersToQuestion(question, rawAnswers)
      updatedAnswersToQuestions        = submission.answersToQuestions + (questionId -> validatedAnswers)
      // we assume no recursion needed for the next 3 steps - otherwise the ask when question structure must have been implemented in a complex recursive mess
      updatedQuestionnaireProgress     = deriveProgressOfQuestionnaires(submission.allQuestionnaires, context, updatedAnswersToQuestions)
      questionsThatShouldBeAsked       = updatedQuestionnaireProgress.flatMap(_._2.questionsToAsk).toList
      finalAnswersToQuestions          = updatedAnswersToQuestions.filter { case (qid, _) => questionsThatShouldBeAsked.contains(qid) }
      updatedSubmission                = submission.copy(answersToQuestions = finalAnswersToQuestions)
      extendedSubmission               = ExtendedSubmission(updatedSubmission, updatedQuestionnaireProgress)
    } yield extendedSubmission
  }

  def deriveProgressOfQuestionnaire(questionnaire: Questionnaire, context: Context, answersToQuestions: AnswersToQuestions): QuestionnaireProgress = {
    val questionsToAsk = AnswerQuestion.questionsToAsk(questionnaire, context, answersToQuestions)
    val (answeredQuestions, unansweredQuestions) = questionsToAsk.partition(answersToQuestions.contains)
    val state = (unansweredQuestions.headOption, answeredQuestions.nonEmpty) match {
      case (None, true)       => Completed
      case (None, false)      => NotApplicable
      case (_, true)          => InProgress
      case (_, false)         => NotStarted
    }

    QuestionnaireProgress(state, questionsToAsk)
  }
    
  def deriveProgressOfQuestionnaires(questionnaires: NonEmptyList[Questionnaire], context: Context, answersToQuestions: AnswersToQuestions): Map[QuestionnaireId, QuestionnaireProgress] = {
    questionnaires.toList.map(q => (q.id -> deriveProgressOfQuestionnaire(q, context, answersToQuestions))).toMap
  }

  def validateAnswersToQuestion(question: Question, rawAnswers: Option[NonEmptyList[String]]): Either[String, ActualAnswer] = {
    (rawAnswers, question) match {
      case (Some(raw), q: NonOptionalQuestion)     => validateAnswerWhenNonOptional(q, raw)
      case (Some(raw), OptionalQuestion(inner, _)) => validateAnswerWhenNonOptional(inner, raw).map(a => OptionalAnswer[inner.AnswerType](Some(a.asInstanceOf[inner.AnswerType])))
      case (None, OptionalQuestion(inner, _))      => Either.right(OptionalAnswer[inner.AnswerType](None))
      case (None, _)                               => Either.left("Non-optional Question must have an answer")
    }
  }

  def validateAnswerWhenNonOptional(question: NonOptionalQuestion, rawAnswers: NonEmptyList[String]): Either[String, ActualAnswer] = {
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
