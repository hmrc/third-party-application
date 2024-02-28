/*
 * Copyright 2023 HM Revenue & Customs
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

import scala.collection.immutable.ListMap

import cats.data.NonEmptyList

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

class MarkAnswerSpec extends HmrcSpec {

  object TestQuestionnaires extends SubmissionsTestData {
    val question1Id = Question.Id.random

    val questionnaireAId = Questionnaire.Id.random

    val YES = ActualAnswer.SingleChoiceAnswer("Yes")
    val NO  = ActualAnswer.SingleChoiceAnswer("No")

    val ANSWER_FAIL = "a1"
    val ANSWER_WARN = "a2"
    val ANSWER_PASS = "a3"

    def buildSubmissionFromQuestions(questions: Question*) = {
      val questionnaire = Questionnaire(
        id = questionnaireAId,
        label = Questionnaire.Label("Questionnaie"),
        questions = NonEmptyList.fromListUnsafe(questions.map((q: Question) => QuestionItem(q)).toList)
      )

      val oneGroups = NonEmptyList.of(GroupOfQuestionnaires("Group", NonEmptyList.of(questionnaire)))
      aSubmission.copy(groups = oneGroups)
    }

    def buildYesNoQuestion(id: Question.Id, yesMark: Mark, noMark: Mark) = Question.YesNoQuestion(
      id,
      Wording("wording1"),
      Some(Statement(StatementText("Statement1"))),
      None,
      None,
      None,
      yesMark,
      noMark
    )

    def buildTextQuestion(id: Question.Id) = Question.TextQuestion(
      id,
      Wording("wording1"),
      Some(Statement(StatementText("Statement1"))),
      absence = Some(("blah blah blah", Mark.Fail))
    )

    def buildAcknowledgementOnlyQuestion(id: Question.Id) = Question.AcknowledgementOnly(
      id,
      Wording("wording1"),
      Some(Statement(StatementText("Statement1")))
    )

    def buildMultiChoiceQuestion(id: Question.Id, answerMap: ListMap[PossibleAnswer, Mark]) = Question.MultiChoiceQuestion(
      id,
      Wording("wording1"),
      Some(Statement(StatementText("Statement1"))),
      None,
      None,
      None,
      answerMap
    )

    object YesNoQuestionnaireData {
      val question1 = buildYesNoQuestion(question1Id, Mark.Pass, Mark.Warn)
      val question2 = buildYesNoQuestion(question2Id, Mark.Pass, Mark.Warn)

      val submission = buildSubmissionFromQuestions(question1, question2)
    }

    object OptionalQuestionnaireData {
      val question1 = buildTextQuestion(question1Id)

      val submission = buildSubmissionFromQuestions(question1)
    }

    object AcknowledgementOnlyQuestionnaireData {
      val question1 = buildAcknowledgementOnlyQuestion(question1Id)

      val submission = buildSubmissionFromQuestions(question1)
    }

    object MultiChoiceQuestionnaireData {

      val question1 =
        buildMultiChoiceQuestion(question1Id, ListMap(PossibleAnswer(ANSWER_PASS) -> Mark.Pass, PossibleAnswer(ANSWER_WARN) -> Mark.Warn, PossibleAnswer(ANSWER_FAIL) -> Mark.Fail))

      val submission = buildSubmissionFromQuestions(question1)
    }
  }

  import TestQuestionnaires._

  def withYesNoAnswers(answer1: ActualAnswer.SingleChoiceAnswer, answer2: ActualAnswer.SingleChoiceAnswer): Submission = {
    require(List(YES, NO).contains(answer1))
    require(List(YES, NO).contains(answer2))

    YesNoQuestionnaireData.submission.hasCompletelyAnsweredWith(Map(question1Id -> answer1, question2Id -> answer2))
  }

  def withSingleOptionalQuestionNoAnswer(): Submission = {
    OptionalQuestionnaireData.submission.hasCompletelyAnsweredWith(Map(question1Id -> ActualAnswer.NoAnswer))
  }

  def withSingleOptionalQuestionAndAnswer(): Submission = {
    OptionalQuestionnaireData.submission.hasCompletelyAnsweredWith(Map(question1Id -> ActualAnswer.TextAnswer("blah blah")))
  }

  def withAcknowledgementOnlyAnswers(): Submission = {
    AcknowledgementOnlyQuestionnaireData.submission.hasCompletelyAnsweredWith(Map(question1Id -> ActualAnswer.AcknowledgedAnswer))
  }

  def withMultiChoiceAnswers(answers: String*): Submission = {
    MultiChoiceQuestionnaireData.submission.hasCompletelyAnsweredWith(Map(question1Id -> ActualAnswer.MultipleChoiceAnswer(answers.toList.toSet)))
  }

  "markSubmission" should {
    "not accept incomplete submissions without throwing exception" in {
      intercept[IllegalArgumentException] {
        MarkAnswer.markSubmission(aSubmission)
      }
    }

    "return Fail for NoAnswer in optional text question" in {
      val markedQuestions = MarkAnswer.markSubmission(withSingleOptionalQuestionNoAnswer())

      markedQuestions shouldBe Map(question1Id -> Mark.Fail)
    }

    "return Pass for Answer in optional text question" in {
      val markedQuestions = MarkAnswer.markSubmission(withSingleOptionalQuestionAndAnswer())

      markedQuestions shouldBe Map(question1Id -> Mark.Pass)
    }

    "return the correct marks for Single Choice questions" in {
      val markedQuestions = MarkAnswer.markSubmission(withYesNoAnswers(YES, NO))

      markedQuestions shouldBe Map(question1Id -> Mark.Pass, question2Id -> Mark.Warn)
    }

    "return the correct mark for AcknowledgementOnly question" in {
      val markedQuestions = MarkAnswer.markSubmission(withAcknowledgementOnlyAnswers())

      markedQuestions shouldBe Map(question1Id -> Mark.Pass)
    }

    "return Fail for Multiple Choice question" in {
      val markedQuestions = MarkAnswer.markSubmission(withMultiChoiceAnswers(ANSWER_FAIL))

      markedQuestions shouldBe Map(question1Id -> Mark.Fail)
    }

    "return Warn for Multiple Choice question" in {
      val markedQuestions = MarkAnswer.markSubmission(withMultiChoiceAnswers(ANSWER_WARN))

      markedQuestions shouldBe Map(question1Id -> Mark.Warn)
    }

    "return Pass for Multiple Choice question" in {
      val markedQuestions = MarkAnswer.markSubmission(withMultiChoiceAnswers(ANSWER_PASS))

      markedQuestions shouldBe Map(question1Id -> Mark.Pass)
    }

    "return Fail for Multiple Choice question if answer includes a single failure for the first answer" in {
      val markedQuestions = MarkAnswer.markSubmission(withMultiChoiceAnswers(ANSWER_FAIL, ANSWER_WARN, ANSWER_PASS))

      markedQuestions shouldBe Map(question1Id -> Mark.Fail)
    }

    "return Fail for Multiple Choice question if answer includes a single failure for the last answer" in {
      val markedQuestions = MarkAnswer.markSubmission(withMultiChoiceAnswers(ANSWER_PASS, ANSWER_WARN, ANSWER_FAIL))

      markedQuestions shouldBe Map(question1Id -> Mark.Fail)
    }

    "return Warn for Multiple Choice question if answer includes a single warnng and no failure" in {
      val markedQuestions = MarkAnswer.markSubmission(withMultiChoiceAnswers(ANSWER_WARN, ANSWER_PASS))

      markedQuestions shouldBe Map(question1Id -> Mark.Warn)
    }
  }
}
