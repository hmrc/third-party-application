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

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.ExtendedSubmission
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import cats.data.NonEmptyList
import org.joda.time.DateTime
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import scala.collection.immutable.ListMap

class MarkAnswerSpec extends HmrcSpec {

  object TestQuestionnaires {
    val submissionId = SubmissionId.random
    val applicationId = ApplicationId.random
    
    val question1Id = QuestionId.random
    val question2Id = QuestionId.random
    
    val questionnaireAId = QuestionnaireId.random

    val YES = SingleChoiceAnswer("Yes")
    val NO = SingleChoiceAnswer("No")

    val ANSWER_FAIL = "a1"
    val ANSWER_WARN = "a2"
    val ANSWER_PASS = "a3"

    def buildSubmissionFromQuestions(questions: Question*) = {
      val questionnaire = Questionnaire(
        id = questionnaireAId,
        label = Label("Questionnaie"),
        questions = NonEmptyList.fromListUnsafe(questions.map((q:Question) => QuestionItem(q)).toList)
      )

      val groups = GroupOfQuestionnaires("Group", NonEmptyList.of(questionnaire))
      Submission(submissionId, applicationId, DateTime.now, NonEmptyList.of(groups), Map.empty)
    }

    def buildYesNoQuestion(id: QuestionId, yesMark: Mark, noMark: Mark) = YesNoQuestion(
        id,
        Wording("wording1"),
        Statement(StatementText("Statement1")),
        yesMark,
        noMark
      )

    def buildTextQuestion(id: QuestionId) = TextQuestion(
        id,
        Wording("wording1"),
        Statement(StatementText("Statement1")),
        Some(("blah blah blah", Fail))
      )
    
    def buildAcknowledgementOnlyQuestion(id: QuestionId) = AcknowledgementOnly(
        id,
        Wording("wording1"),
        Statement(StatementText("Statement1"))        
      )

    def buildMultiChoiceQuestion(id: QuestionId, answerMap: ListMap[PossibleAnswer, Mark]) = MultiChoiceQuestion(
        id,
        Wording("wording1"),
        Statement(StatementText("Statement1")),
        answerMap        
      )

    object YesNoQuestionnaireData {
      val question1 = buildYesNoQuestion(question1Id, Pass, Warn)
      val question2 = buildYesNoQuestion(question2Id, Pass, Warn)

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
      val question1 = buildMultiChoiceQuestion(question1Id, ListMap(PossibleAnswer(ANSWER_PASS) -> Pass, PossibleAnswer(ANSWER_WARN) -> Warn, PossibleAnswer(ANSWER_FAIL) -> Fail))

      val submission = buildSubmissionFromQuestions(question1)
    }
  }

  import TestQuestionnaires._

  def withYesNoAnswers(answer1: SingleChoiceAnswer, answer2: SingleChoiceAnswer): Submission = {
    require(List(YES,NO).contains(answer1))
    require(List(YES,NO).contains(answer2))

    YesNoQuestionnaireData.submission.copy(answersToQuestions = Map(question1Id -> answer1, question2Id -> answer2))
  }

  def withSingleOptionalQuestionNoAnswer(): Submission = {
    OptionalQuestionnaireData.submission.copy(answersToQuestions = Map(question1Id -> NoAnswer))
  }
  def withSingleOptionalQuestionAndAnswer(): Submission = {
    OptionalQuestionnaireData.submission.copy(answersToQuestions = Map(question1Id -> TextAnswer("blah blah")))
  }
  def withAcknowledgementOnlyAnswers(): Submission = {
    AcknowledgementOnlyQuestionnaireData.submission.copy(answersToQuestions = Map(question1Id -> AcknowledgedAnswer))
  }
  def withMultiChoiceAnswers(answers: String*): Submission = {
    MultiChoiceQuestionnaireData.submission.copy(answersToQuestions = Map(question1Id -> MultipleChoiceAnswer(answers.toList.toSet)))
  }

  def extend(submission: Submission): ExtendedSubmission = 
    ExtendedSubmission(submission, Map.empty[QuestionnaireId, QuestionnaireProgress])

  "markSubmission" should {
    "not accept incomplete submissions without throwing exception" in {
      val incompleteSubmission = mock[ExtendedSubmission]
      when(incompleteSubmission.isCompleted).thenReturn(false)

      intercept[IllegalArgumentException] {
        MarkAnswer.markSubmission(incompleteSubmission)
      }
    }

    "return an empty map if there are no questions" in {
      val extSubmissionWithNoAnswers = extend(YesNoQuestionnaireData.submission)

      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithNoAnswers)

      markedQuestions shouldBe Map.empty[QuestionnaireId, Mark]
    }

    "return Fail for NoAnswer in optional text question" in {
      val extSubmissionWithOptionalAnswers = extend(withSingleOptionalQuestionNoAnswer())
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithOptionalAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Fail)
    }

    "return Pass for Answer in optional text question" in {
      val extSubmissionWithOptionalAnswers = extend(withSingleOptionalQuestionAndAnswer())
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithOptionalAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Pass)
    }

    "return the correct marks for Single Choice questions" in {
      val extSubmissionWithSingleChoiceAnswers = extend(withYesNoAnswers(YES, NO))
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithSingleChoiceAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Pass, question2Id -> Warn)
    }

    "return the correct mark for AcknowledgementOnly question" in {
      val extSubmissionWithAcknowledgementOnlyAnswers = extend(withAcknowledgementOnlyAnswers())
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithAcknowledgementOnlyAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Pass)
    }

    "return Fail for Multiple Choice question" in {
      val extSubmissionWithMultiChoiceAnswers = extend(withMultiChoiceAnswers(ANSWER_FAIL))
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithMultiChoiceAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Fail)      
    }

    "return Warn for Multiple Choice question" in {
      val extSubmissionWithMultiChoiceAnswers = extend(withMultiChoiceAnswers(ANSWER_WARN))
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithMultiChoiceAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Warn)      
    }

    "return Pass for Multiple Choice question" in {
      val extSubmissionWithMultiChoiceAnswers = extend(withMultiChoiceAnswers(ANSWER_PASS))
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithMultiChoiceAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Pass)      
    }

    "return Fail for Multiple Choice question if answer includes a single failure for the first answer" in {
      val extSubmissionWithMultiChoiceAnswers = extend(withMultiChoiceAnswers(ANSWER_FAIL, ANSWER_WARN, ANSWER_PASS))
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithMultiChoiceAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Fail)      
    }

    "return Fail for Multiple Choice question if answer includes a single failure for the last answer" in {
      val extSubmissionWithMultiChoiceAnswers = extend(withMultiChoiceAnswers( ANSWER_PASS, ANSWER_WARN, ANSWER_FAIL))
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithMultiChoiceAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Fail)      
    }

    "return Warn for Multiple Choice question if answer includes a single warnng and no failure" in {
      val extSubmissionWithMultiChoiceAnswers = extend(withMultiChoiceAnswers(ANSWER_WARN, ANSWER_PASS))
      
      val markedQuestions = MarkAnswer.markSubmission(extSubmissionWithMultiChoiceAnswers)
      
      markedQuestions shouldBe Map(question1Id -> Warn)      
    }
  }
}