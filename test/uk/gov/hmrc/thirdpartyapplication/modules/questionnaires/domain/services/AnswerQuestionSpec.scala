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


import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks.QuestionBuilder
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import org.scalatest.Inside
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories.QuestionnaireDAO

trait AsIdsHelpers {
 
  implicit class ListQIdSyntax(questionItems: List[QuestionItem]) {
    def asIds(): List[QuestionId] = {
      questionItems.map(_.question.id)
    }
  }
  implicit class NELQIdSyntax(questionItems: NonEmptyList[QuestionItem]) {
    def asIds(): List[QuestionId] = {
      questionItems.toList.map(_.question.id)
    }
  }
}

class AnswerQuestionSpec extends HmrcSpec with Inside with QuestionBuilder with AsIdsHelpers {
  
  trait Setup extends SubmissionsTestData
  val blankContext : Context = Map.empty

  val YesAnswer = Some(NonEmptyList.of("Yes"))
  val NoAnswer = Some(NonEmptyList.of("No"))
  
  "AnswerQuestion" when {
    "answer is called" should {
      "return updated submission" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, questionId, YesAnswer, blankContext)

        inside(after.right.value) {
          case ExtendedSubmission(submission, _) =>
            submission.id shouldBe submission.id
            submission.applicationId shouldBe submission.applicationId
            submission.startedOn shouldBe submission.startedOn
            submission.groups shouldBe submission.groups
            submission.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("Yes")
        }
      }

      "return updated submission after overwriting answer" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, questionId, YesAnswer, blankContext)
        val s2 = AnswerQuestion.recordAnswer(s1.right.value.submission, questionId, NoAnswer, blankContext)

        inside(s2.right.value) {
          case ExtendedSubmission(submission, _) =>
            submission.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
        }
      }

      "return updated submission does not loose other answers in same questionnaire" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, question2Id, YesAnswer, blankContext)

        val s2 = AnswerQuestion.recordAnswer(s1.right.value.submission, questionId, NoAnswer, blankContext)

        inside(s2.right.value) {
          case ExtendedSubmission(submission, _) =>
            submission.answersToQuestions.get(question2Id).value shouldBe SingleChoiceAnswer("Yes")
            submission.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
        }
      }

      "return updated submission does not loose other answers in other questionnaires" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, questionAltId, YesAnswer, blankContext)

        val s2 = AnswerQuestion.recordAnswer(s1.right.value.submission, questionId, NoAnswer, blankContext)

        inside(s2.right.value) {
          case ExtendedSubmission(submission, _) =>
            submission.answersToQuestions.get(questionAltId).value shouldBe SingleChoiceAnswer("Yes")
            submission.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
        }
      }

      "return left when question is not part of the questionnaire" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, QuestionId.random, YesAnswer, blankContext)

        after.left.value
      }

      "return left when answer is not valid" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, QuestionnaireDAO.Questionnaires.DevelopmentPractices.question1.id, Some(NonEmptyList.of("Bob")), blankContext)

        after.left.value
      }

      // "return left when answer is absent for a non-optional question" in new Setup {
      //   val after = AnswerQuestion.recordAnswer(submission, QuestionId.random, None, blankContext)

      //   after.left.value
      // }
    }

    "deriveProgressOfQuestionnaire" should {
      import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories.QuestionnaireDAO.Questionnaires._
      val emptyAnswers = Map.empty[QuestionId, ActualAnswer]

      "return not started, with answerable questions when nothing answered" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(DevelopmentPractices.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(NotStarted, DevelopmentPractices.questionnaire.questions.asIds)
      }

      "return in progress, with answerable questions when a question is answered" in new Setup {
        val context = simpleContext
        val answers = Map(ServiceManagementPractices.question1.id -> SingleChoiceAnswer("Yes"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(ServiceManagementPractices.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(InProgress, ServiceManagementPractices.questionnaire.questions.asIds)
      }
      
      "return completed, with answerable questions when all questions are answered" in new Setup {
        val context = simpleContext
        val answers = Map(ServiceManagementPractices.question1.id -> SingleChoiceAnswer("Yes"), ServiceManagementPractices.question2.id -> SingleChoiceAnswer("Yes"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(ServiceManagementPractices.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(Completed, ServiceManagementPractices.questionnaire.questions.asIds)
      }

      "return not started for questionnaire that skips second question due to context regardless of answers" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(CustomersAuthorisingYourSoftware.questionnaire, context, answers)
        val listOfQuestions = List(CustomersAuthorisingYourSoftware.question1.id, CustomersAuthorisingYourSoftware.question3.id, CustomersAuthorisingYourSoftware.question4.id)
        res shouldBe QuestionnaireProgress(NotStarted, listOfQuestions)
      }

      "return not applicable and no question for questionnaire that skips all questions due to context if appropriate context" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(FraudPreventionHeaders.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(NotApplicable, List.empty)
      }

      "return not started and all questions except second and third questions based on not having the inclusive answer of the first" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(SoftwareSecurity.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(NotStarted, List(SoftwareSecurity.question1.id))
      }

      "return completed and all questions except second and third questions based on answer of the first excluding the others" in new Setup {
        val context = simpleContext
        val answers = Map(SoftwareSecurity.question1.id -> SingleChoiceAnswer("No"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(SoftwareSecurity.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(Completed, List(SoftwareSecurity.question1.id))
      }

      "return in progress and all questions for questionnaire that skips second and third questions based on answer of the first including the others" in new Setup {
        val context = simpleContext
        val answers = Map(SoftwareSecurity.question1.id -> SingleChoiceAnswer("Yes"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(SoftwareSecurity.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(InProgress, SoftwareSecurity.questionnaire.questions.asIds)
      }       
    }

    import AnswerQuestion.{validateAnswersToQuestion, validateAnswerWhenNonOptional}
    
    "call validateAnswerWhenNonOptional for single choice questions" should {
      val question = yesNoQuestion(1)

      "return 'right(answer) when the first answer is valid" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("Yes")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      "return 'right(answer) when the first answer is valid regardless of subsequent answers" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("Yes", "blah")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      
      "return 'left when the first answer is invalid" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("Yodel")) shouldBe 'left
      }

      "return 'left when the first answer is invalid even when subsequent answers are correct" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("Yodel", "Yes")) shouldBe 'left
      }
    }

    "call validateAnswerWhenNonOptional for multiple choice questions" should {
      val question = multichoiceQuestion(1, "one","two", "three")

      "return 'right(answers) when all answers are valid" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("two", "three")).right.value shouldBe MultipleChoiceAnswer(Set("two", "three"))
      }

      "return 'left when not all answers are valid" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("two", "three", "yodel")) shouldBe 'left
      }
    }

    "call validateAnswerWhenNonOptional for text question" should {
      val question = textQuestion(1)

      "return 'right when an answer is given" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("answered")).right.value shouldBe TextAnswer("answered")
      }
      
      "return 'left when the answer is blank" in {
        validateAnswerWhenNonOptional(question, NonEmptyList.of("")) shouldBe 'left
      }
    }
  }
}
