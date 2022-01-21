/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.submissions.domain.services


import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import cats.data.NonEmptyList
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.QuestionBuilder
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import org.scalatest.Inside
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO

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
  val blankContext : AskWhen.Context = Map.empty

  val YesAnswer = List("Yes")
  val NoAnswer = List("No")
  
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
            submission.latestInstance.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("Yes")
        }
      }

      "return updated submission after overwriting answer" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, questionId, YesAnswer, blankContext)
        val s2 = AnswerQuestion.recordAnswer(s1.right.value.submission, questionId, NoAnswer, blankContext)

        inside(s2.right.value) {
          case ExtendedSubmission(submission, _) =>
            submission.latestInstance.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
        }
      }

      "return updated submission does not loose other answers in same questionnaire" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, question2Id, YesAnswer, blankContext)

        val s2 = AnswerQuestion.recordAnswer(s1.right.value.submission, questionId, NoAnswer, blankContext)

        inside(s2.right.value) {
          case ExtendedSubmission(submission, _) =>
            submission.latestInstance.answersToQuestions.get(question2Id).value shouldBe SingleChoiceAnswer("Yes")
            submission.latestInstance.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
        }
      }

      "return updated submission does not loose other answers in other questionnaires" in new Setup {
        val s1 = AnswerQuestion.recordAnswer(submission, questionAltId, YesAnswer, blankContext)

        val s2 = AnswerQuestion.recordAnswer(s1.right.value.submission, questionId, NoAnswer, blankContext)

        inside(s2.right.value) {
          case ExtendedSubmission(submission, _) =>
            submission.latestInstance.answersToQuestions.get(questionAltId).value shouldBe SingleChoiceAnswer("Yes")
            submission.latestInstance.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("No")
        }
      }

      "return left when question is not part of the questionnaire" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, QuestionId.random, YesAnswer, blankContext)

        after.left.value
      }

      "return left when answer is not valid" in new Setup {
        val after = AnswerQuestion.recordAnswer(submission, QuestionnaireDAO.Questionnaires.DevelopmentPractices.question1.id, List("Bob"), blankContext)

        after.left.value
      }
    }

    "deriveProgressOfQuestionnaire" should {
      import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO.Questionnaires._
      val emptyAnswers = Map.empty[QuestionId, ActualAnswer]

      "return not started, with answerable questions when nothing answered" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(DevelopmentPractices.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(QuestionnaireState.NotStarted, DevelopmentPractices.questionnaire.questions.asIds)
      }

      "return in progress, with answerable questions when a question is answered" in new Setup {
        val context = soldContext
        val answers = Map(ServiceManagementPractices.question1.id -> SingleChoiceAnswer("Yes"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(ServiceManagementPractices.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(QuestionnaireState.InProgress, ServiceManagementPractices.questionnaire.questions.asIds)
      }
      
      "return completed, with answerable questions when all questions are answered" in new Setup {
        val context = soldContext
        val answers = Map(ServiceManagementPractices.question1.id -> SingleChoiceAnswer("Yes"), ServiceManagementPractices.question2.id -> SingleChoiceAnswer("Yes"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(ServiceManagementPractices.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(QuestionnaireState.Completed, ServiceManagementPractices.questionnaire.questions.asIds)
      }

      "return not started for questionnaire that skips third question due to context regardless of answers" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(CustomersAuthorisingYourSoftware.questionnaire, context, answers)
        val listOfQuestions = List(CustomersAuthorisingYourSoftware.question1.id, CustomersAuthorisingYourSoftware.question2.id, CustomersAuthorisingYourSoftware.question4.id, CustomersAuthorisingYourSoftware.question5.id)
        res shouldBe QuestionnaireProgress(QuestionnaireState.NotStarted, listOfQuestions)
      }

      "return not applicable and no question for questionnaire that skips all questions due to context if appropriate context" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(FraudPreventionHeaders.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(QuestionnaireState.NotApplicable, List.empty)
      }

      "return not started and all questions except second and third questions based on not having the inclusive answer of the first" in new Setup {
        val context = simpleContext
        val answers = emptyAnswers
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(SoftwareSecurity.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(QuestionnaireState.NotStarted, List(SoftwareSecurity.question1.id))
      }

      "return completed and all questions except second and third questions based on answer of the first excluding the others" in new Setup {
        val context = simpleContext
        val answers = Map(SoftwareSecurity.question1.id -> SingleChoiceAnswer("No"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(SoftwareSecurity.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(QuestionnaireState.Completed, List(SoftwareSecurity.question1.id))
      }

      "return in progress and all questions for questionnaire that skips second and third questions based on answer of the first including the others" in new Setup {
        val context = simpleContext
        val answers = Map(SoftwareSecurity.question1.id -> SingleChoiceAnswer("Yes"))
        val res = AnswerQuestion.deriveProgressOfQuestionnaire(SoftwareSecurity.questionnaire, context, answers)

        res shouldBe QuestionnaireProgress(QuestionnaireState.InProgress, SoftwareSecurity.questionnaire.questions.asIds)
      }       
    }
  }
}
