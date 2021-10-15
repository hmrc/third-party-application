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

object NextQuestion {
  
  type ActualAnswers = Map[QuestionId, ActualAnswer]

  type Error = String

  protected def shouldAsk(next: QuestionItem, context: Context, answers: ActualAnswers): Boolean = {
    next.askWhen match {
      case AlwaysAsk => true
      case AskWhenContext(contextKey, expectedValue) => context.get(contextKey).map(_.equalsIgnoreCase(expectedValue)).getOrElse(false)
      case AskWhenAnswer(questionId, expectedAnswer) => answers.get(questionId).map(_ == expectedAnswer).getOrElse(false)
    }
  }
  
  def deriveNextQuestions(savedSubmission: Submission, context: Context): Map[QuestionnaireId, QuestionId] = {
    val questionnaires = savedSubmission.allQuestionnaires
    val answersToQuestions = savedSubmission.answersToQuestions

    questionnaires.map(qn => (qn.id -> NextQuestion.getNextQuestion(qn, context, answersToQuestions).map(_.id)))
      .collect {
        case (q1,Some(q2)) => (q1, q2)
      }
      .toMap
  }

  def getNextQuestion(questionnaire: Questionnaire, context: Context, answers: ActualAnswers): Option[Question] = {
    def checkNext(fi: QuestionItem): Option[Question] = {
      if(shouldAsk(fi, context, answers)) {
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

    findFirst(questionnaire.questions.toList)
  }

}
