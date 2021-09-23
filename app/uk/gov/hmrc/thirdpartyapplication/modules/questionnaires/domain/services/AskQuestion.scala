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

import cats.implicits._
import cats.data.NonEmptyList

object AskQuestion {
  type Context = Map[String, String]
  
  type ActualAnswers = Map[QuestionId, ActualAnswer]

  type Error = String

  protected def shouldAsk(context: Context)(next: QuestionItem, answers: ActualAnswers): Boolean = {
    next.askWhen match {
      case AlwaysAsk => true
      case AskWhenContext(contextKey, expectedValue) => context.get(contextKey).map(_.equalsIgnoreCase(expectedValue)).getOrElse(false)
      case AskWhenAnswer(questionId, expectedAnswer) => answers.get(questionId).map(_ == expectedAnswer).getOrElse(false)
    }
  }

  def getNextQuestion(context: Context)(questionnaire: Questionnaire, answers: ActualAnswers): Option[Question] = {
    def checkNext(fi: QuestionItem): Option[Question] = {
      if(shouldAsk(context)(fi, answers)) {
        if(answers.contains(fi.question.id)) {
          None
        }
        else {
          Some(fi.question)
        }
      }
      else {
        None
      }
    }

    def findFirst(fis: List[QuestionItem]): Option[Question] = {
      fis match {
        case Nil => None
        case head :: tail =>
          checkNext(head) match {
            case Some(q) => Some(q)
            case None => findFirst(tail)
          }
      }
    }

    findFirst(questionnaire.questions)
  }

  def validateAnswersToQuestion(question: Question, answers: NonEmptyList[String]): Either[Error, ActualAnswer] = {
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
