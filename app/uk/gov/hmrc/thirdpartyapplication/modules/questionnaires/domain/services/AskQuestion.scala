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

object AskQuestion {
  type Context = Map[String, String]
  
  type Answers = Map[QuestionId, Answer]

  type Error = String

  private def shouldAsk(context: Context)(next: QuestionItem, answers: Answers): Boolean = {
    next.askWhen match {
      case AlwaysAsk => true
      case AskWhenContext(contextKey, expectedValue) => context.get(contextKey).map(_.equalsIgnoreCase(expectedValue)).getOrElse(false)
      case AskWhenAnswer(questionId, expectedAnswer) => answers.get(questionId).map(_ == expectedAnswer).getOrElse(false)
    }
  }

  def getNextQuestion(context: Context)(questionnaire: Questionnaire, answers: Answers): Option[Question] = {
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

  def processAnswer(question: Question, answers: List[String]): Either[Error, Answer] = {
    question match {
      case q: SingleChoiceQuestion => 
        Either.fromOption(
          answers
          .filter(answer => q.choices.contains(QuestionChoice(answer)))
          .headOption
          .map(SingleChoiceAnswer(_))
          , "The answer is not valid for this question"
        )
      case q: MultiChoiceQuestion =>
        val (valid, invalid) = answers.partition(answer => q.choices.contains(QuestionChoice(answer)))
        (valid, invalid) match {
          case (Nil, Nil)           => "No answers provided".asLeft
          case (_, h::tail)         => "Some answers are not valid for this question".asLeft
          case (validAnswers, Nil)  => MultipleChoiceAnswer(validAnswers.toSet).asRight
        }
      case q: TextQuestion => TextAnswer(answers.head).asRight
    }
  }
}