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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.services

import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AskQuestion._
import cats.data.NonEmptyList
import scala.collection.immutable.ListSet

class AskQuestionSpec extends HmrcSpec {
  def yesNoQuestion(counter: Int): SingleChoiceQuestion = {
    YesNoQuestion(
      QuestionId.random,
      Wording(s"Wording$counter"),
      Statement(List())
    )
  }
  
  def multichoiceQuestion(counter: Int, choices: String*): MultiChoiceQuestion = {
    MultiChoiceQuestion(
      QuestionId.random,
      Wording(s"Wording$counter"),
      Statement(List()),
      ListSet(choices.map(c => QuestionChoice(c)): _*)
    )
  }

  def textQuestion(counter: Int): TextQuestion = {
    TextQuestion(
      QuestionId.random,
      Wording(s"Wording$counter"),
      Statement(List())
    )
  }
  
  val blankContext : Context = Map.empty
  val noAnswers : Answers = Map.empty
  
  "AskQuestion" when {
    "call getNextQuestion" should {
      "if no conditional questions are present" should {
        val question1 = yesNoQuestion(1)
        val question2 = yesNoQuestion(2)
        val q = Questionnaire(
          id = QuestionnaireId.random,
          label = Label("questionnaire"),
          questions = List(
            QuestionItem(question1), 
            QuestionItem(question2) 
          )
        )
        
        "return the first question when nothing is answered" in {
          getNextQuestion(blankContext)(q, noAnswers).value shouldBe question1
        }
        
        "return the second question when the first is answered" in {
          val firstAnswered = noAnswers + (question1.id -> mock[Answer])
          getNextQuestion(blankContext)(q, firstAnswered).value shouldBe question2
        }

        "return none when all are answered" in {
          val allAnswered = noAnswers + (question1.id -> mock[Answer]) + (question2.id -> mock[Answer])
          getNextQuestion(blankContext)(q, allAnswered) shouldBe None
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
          questions = List(
            QuestionItem(question1, AskWhenContext(matchingKey, matchingValue)), 
            QuestionItem(question2) 
          )
        )

        "return the first question when context has matching key and value" in {
          val context = Map(matchingKey -> matchingValue)

          getNextQuestion(context)(q, noAnswers).value shouldBe question1
        }

        "return the second question when context has matching key but not the matching value" in {
          val context = Map(matchingKey -> noMatchingValue)

          getNextQuestion(context)(q, noAnswers).value shouldBe question2
        }

        "return the second question when context has mismatching key" in {
          val context = Map(mismatchKey -> noMatchingValue)

          getNextQuestion(context)(q, noAnswers).value shouldBe question2
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
          questions = List(
            QuestionItem(question1), 
            QuestionItem(question2, AskWhenAnswer(question1, matchingAnswer.value))
          )
        )

        "return the second question when answers contains matching answer for question 1" in {
          val answeredFirstMatching = noAnswers + (question1.id -> matchingAnswer)
          getNextQuestion(blankContext)(q, answeredFirstMatching).value shouldBe question2
        }

        "return none when answers contains a non matching answer for question 1" in {
          val answeredFirstMatching = noAnswers + (question1.id -> noMatchingAnswer)
          getNextQuestion(blankContext)(q, answeredFirstMatching) shouldBe None
        }
      }
    }

    "call processAnswer for single choice questions" should {
      val question = yesNoQuestion(1)

      "return 'right(answer) when the first answer is valid" in {
        processAnswer(question, NonEmptyList.of("Yes")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      "return 'right(answer) when the first answer is valid regardless of subsequent answers" in {
        processAnswer(question, NonEmptyList.of("Yes", "blah")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      
      "return 'left when the first answer is invalid" in {
        processAnswer(question, NonEmptyList.of("Yodel")) shouldBe 'left
      }

      "return 'left when the first answer is invalid even when subsequent answers are correct" in {
        processAnswer(question, NonEmptyList.of("Yodel", "Yes")) shouldBe 'left
      }
    }

    "call processAnswer for multiple choice questions" should {
      val question = multichoiceQuestion(1, "one","two", "three")

      "return 'right(answers) when all answers are valid" in {
        processAnswer(question, NonEmptyList.of("two", "three")).right.value shouldBe MultipleChoiceAnswer(Set("two", "three"))
      }

      "return 'left when not all answers are valid" in {
        processAnswer(question, NonEmptyList.of("two", "three", "yodel")) shouldBe 'left
      }
    }

    "call processAnswer for text question" should {
      val question = textQuestion(1)

      "return 'right when an answer is given" in {
        processAnswer(question, NonEmptyList.of("answered")).right.value shouldBe TextAnswer("answered")
      }
      
      "return 'left when the answer is blank" in {
        processAnswer(question, NonEmptyList.of("")) shouldBe 'left
      }
    }
  }
}
