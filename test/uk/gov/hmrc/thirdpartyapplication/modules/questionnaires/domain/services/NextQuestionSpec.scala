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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.services

import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks.QuestionBuilder
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.NextQuestion._
import cats.data.NonEmptyList

class NextQuestionSpec extends HmrcSpec with QuestionBuilder{

  val blankContext : Context = Map.empty
  val noAnswers: ActualAnswers = Map.empty
  
  "NextQuestion" when {
    "call getNextQuestion" should {
      "if no conditional questions are present" should {
        val question1 = yesNoQuestion(1)
        val question2 = yesNoQuestion(2)
        val q = Questionnaire(
          id = QuestionnaireId.random,
          label = Label("questionnaire"),
          questions = NonEmptyList.of(
            QuestionItem(question1), 
            QuestionItem(question2) 
          )
        )
        
        "return the first question when nothing is answered" in {
          getNextQuestion(q, blankContext, noAnswers).value shouldBe question1
        }
        
        "return the second question when the first is answered" in {
          val firstAnswered = noAnswers + (question1.id -> mock[ActualAnswer])
          getNextQuestion(q, blankContext, firstAnswered).value shouldBe question2
        }

        "return none when all are answered" in {
          val allAnswered = noAnswers + (question1.id -> mock[ActualAnswer]) + (question2.id -> mock[ActualAnswer])
          getNextQuestion(q, blankContext, allAnswered) shouldBe None
        }
      }

      "first question is conditional on context" should {
        val matchingKey = "TEST-ME"
        val mismatchKey = "NOT-ME"
        val matchingValue = "VALUE"
        val noMatchingValue = "NOT_ME"
        
        val question1 = yesNoQuestion(1)
        val question2 = yesNoQuestion(2)
        val q = Questionnaire(
          id = QuestionnaireId.random,
          label = Label("questionnaire"),
          questions = NonEmptyList.of(
            QuestionItem(question1, AskWhenContext(matchingKey, matchingValue)), 
            QuestionItem(question2) 
          )
        )

        "return the first question when context has matching key and value" in {
          val context = Map(matchingKey -> matchingValue)

          getNextQuestion(q, context, noAnswers).value shouldBe question1
        }

        "return the second question when context has matching key but not the matching value" in {
          val context = Map(matchingKey -> noMatchingValue)

          getNextQuestion(q, context, noAnswers).value shouldBe question2
        }

        "return the second question when context has mismatching key" in {
          val context = Map(mismatchKey -> noMatchingValue)

          getNextQuestion(q, context, noAnswers).value shouldBe question2
        }
      }

      "second question is conditional on first answer" should {
        val question1 = yesNoQuestion(1)
        val question2 = yesNoQuestion(2)
        val matchingAnswer = SingleChoiceAnswer("Yes")
        val noMatchingAnswer = SingleChoiceAnswer("No")
        
        val q = Questionnaire(
          id = QuestionnaireId.random,
          label = Label("questionnaire"),
          questions = NonEmptyList.of(
            QuestionItem(question1), 
            QuestionItem(question2, AskWhenAnswer(question1, matchingAnswer.value))
          )
        )

        "return the second question when answers contains matching answer for question 1" in {
          val answeredFirstMatching = noAnswers + (question1.id -> matchingAnswer)
          getNextQuestion(q, blankContext, answeredFirstMatching).value shouldBe question2
        }

        "return none when answers contains a non matching answer for question 1" in {
          val answeredFirstMatching = noAnswers + (question1.id -> noMatchingAnswer)
          getNextQuestion(q, blankContext, answeredFirstMatching) shouldBe None
        }
      }
    }

  }
}
