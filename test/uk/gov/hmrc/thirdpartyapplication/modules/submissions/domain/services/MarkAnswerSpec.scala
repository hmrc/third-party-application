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

class MarkAnswerSpec extends HmrcSpec {

  object TestQuestionnaires {
    val submissionId = SubmissionId.random
    val applicationId = ApplicationId.random
    
    val question1Id = QuestionId.random
    val question2Id = QuestionId.random
    
    val questionnaireAId = QuestionnaireId.random

    val YES = SingleChoiceAnswer("Yes")
    val NO = SingleChoiceAnswer("No")

    object YesNoQuestionnaireData {
      val question1 = YesNoQuestion(
        question1Id,
        Wording("wording1"),
        Statement(StatementText("Statement1")),
        yesMarking = Pass,
        noMarking = Warn
      )
      val question2 = YesNoQuestion(
        question2Id,
        Wording("wording2"),
        Statement(StatementText("Statement2")),
        yesMarking = Pass,
        noMarking = Warn
      )

      val questionnaire = Questionnaire(
        id = questionnaireAId,
        label = Label("Questionnaie"),
        questions = NonEmptyList.of(
          QuestionItem(question1), 
          QuestionItem(question2)
        )
      )

      val groups = GroupOfQuestionnaires("Group", NonEmptyList.of(questionnaire))
      val submission = Submission(submissionId, applicationId, DateTime.now, NonEmptyList.of(groups), Map.empty)
      
    }

    object OptionalQuestionnaireData {
      val question1 = TextQuestion(
        question1Id,
        Wording("wording1"),
        Statement(StatementText("Statement1")),
        Some(("blah blah blah", Fail))
      )

      val questionnaire = Questionnaire(
        id = questionnaireAId,
        label = Label("Questionnaie"),
        questions = NonEmptyList.of(
          QuestionItem(question1)
        )
      )

      val groups = GroupOfQuestionnaires("Group", NonEmptyList.of(questionnaire))
      val submission = Submission(submissionId, applicationId, DateTime.now, NonEmptyList.of(groups), Map.empty)      
    }

    object AcknowledgementOnlyQuestionnaireData {
      val question1 = AcknowledgementOnly(
        question1Id,
        Wording("wording1"),
        Statement(StatementText("Statement1"))        
      )

      val questionnaire = Questionnaire(
        id = questionnaireAId,
        label = Label("Questionnaie"),
        questions = NonEmptyList.of(
          QuestionItem(question1)
        )
      )

      val groups = GroupOfQuestionnaires("Group", NonEmptyList.of(questionnaire))
      val submission = Submission(submissionId, applicationId, DateTime.now, NonEmptyList.of(groups), Map.empty)           
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
  }
}