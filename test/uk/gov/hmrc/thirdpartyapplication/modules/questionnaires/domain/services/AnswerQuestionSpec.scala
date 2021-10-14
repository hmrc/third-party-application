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


import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.mocks.QuestionBuilder
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData


class AnswerQuestionSpec extends HmrcSpec with QuestionBuilder {
  
  trait Setup extends SubmissionsTestData

  "AnswerQuestion" when {
    "answer is called" should {
      "return updated submission" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, questionId, NonEmptyList.of("Yes"))

        val check = after.right.value

        check.id shouldBe submission.id
        check.applicationId shouldBe submission.applicationId
        check.startedOn shouldBe submission.startedOn
        check.groups shouldBe submission.groups
        check.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("Yes")
      }

      "return updated submission after overwriting answer" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, questionId, NonEmptyList.of("Yes"))
        val s2 = AnswerQuestion.recordAnswer(s1.right.value, questionId, NonEmptyList.of("No"))

        val check = s2.right.value

        check.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
      }

      "return updated submission does not loose other answers in same questionnaire" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, question2Id, NonEmptyList.of("Yes"))
        
        val s2 = AnswerQuestion.recordAnswer(s1.right.value, questionId, NonEmptyList.of("No"))

        val check = s2.right.value

        check.answersToQuestions.get(question2Id).value shouldBe SingleChoiceAnswer("Yes")
        check.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
      }

      "return updated submission does not loose other answers in other questionnaires" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, questionAltId, NonEmptyList.of("Yes"))
        
        val s2 = AnswerQuestion.recordAnswer(s1.right.value, questionId, NonEmptyList.of("No"))

        val check = s2.right.value

        check.answersToQuestions.get(questionAltId).value shouldBe SingleChoiceAnswer("Yes")
        check.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
      }

      "return left when question is not part of the questionnaire" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, QuestionId.random, NonEmptyList.of("Yes"))

        after.left.value
      }

      "return left when questionnaire is not in submission" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, QuestionId.random, NonEmptyList.of("Yes"))

        after.left.value
      }

      "return left when answer is not valid" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, QuestionId.random, NonEmptyList.of("Bob"))

        after.left.value
      }
    }

    import AnswerQuestion.validateAnswersToQuestion
    
    "call validateAnswersToQuestion for single choice questions" should {
      val question = yesNoQuestion(1)

      "return 'right(answer) when the first answer is valid" in {
        validateAnswersToQuestion(question, NonEmptyList.of("Yes")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      "return 'right(answer) when the first answer is valid regardless of subsequent answers" in {
        validateAnswersToQuestion(question, NonEmptyList.of("Yes", "blah")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      
      "return 'left when the first answer is invalid" in {
        validateAnswersToQuestion(question, NonEmptyList.of("Yodel")) shouldBe 'left
      }

      "return 'left when the first answer is invalid even when subsequent answers are correct" in {
        validateAnswersToQuestion(question, NonEmptyList.of("Yodel", "Yes")) shouldBe 'left
      }
    }

    "call validateAnswersToQuestion for multiple choice questions" should {
      val question = multichoiceQuestion(1, "one","two", "three")

      "return 'right(answers) when all answers are valid" in {
        validateAnswersToQuestion(question, NonEmptyList.of("two", "three")).right.value shouldBe MultipleChoiceAnswer(Set("two", "three"))
      }

      "return 'left when not all answers are valid" in {
        validateAnswersToQuestion(question, NonEmptyList.of("two", "three", "yodel")) shouldBe 'left
      }
    }

    "call validateAnswersToQuestion for text question" should {
      val question = textQuestion(1)

      "return 'right when an answer is given" in {
        validateAnswersToQuestion(question, NonEmptyList.of("answered")).right.value shouldBe TextAnswer("answered")
      }
      
      "return 'left when the answer is blank" in {
        validateAnswersToQuestion(question, NonEmptyList.of("")) shouldBe 'left
      }
    }
  }
}
