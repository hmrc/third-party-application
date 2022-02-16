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

package uk.gov.hmrc.apiplatform.modules.submissions

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.AskWhen.Context.Keys
import cats.data.NonEmptyList


trait QuestionnaireTestData2 extends QuestionnaireTestData {
  val questionnaire = DevelopmentPractices.questionnaire
  val questionnaireId = questionnaire.id
  val question = questionnaire.questions.head.question
  val questionId = question.id
  val question2Id = questionnaire.questions.tail.head.question.id
  val questionnaireAlt = OrganisationDetails.questionnaire
  val questionnaireAltId = questionnaireAlt.id
  val questionAltId = questionnaireAlt.questions.head.question.id
  val optionalQuestion = CustomersAuthorisingYourSoftware.question4
  val optionalQuestionId = optionalQuestion.id

  val allQuestionnaires = testGroups.flatMap(_.links)

  val expectedAppName = "expectedAppName"

  val answersToQuestions: Submission.AnswersToQuestions = 
    Map(
      testQuestionIdsOfInterest.applicationNameId -> TextAnswer(expectedAppName), 
      testQuestionIdsOfInterest.responsibleIndividualEmailId -> TextAnswer("bob@example.com"),
      testQuestionIdsOfInterest.responsibleIndividualNameId -> TextAnswer("Bob Cratchett")
    )  


  def firstQuestion(questionnaire: Questionnaire) = questionnaire.questions.head.question.id
}

trait StatusTestDataHelper {
  implicit class StatusHistorySyntax(submission: Submission) {
    def hasCompletelyAnsweredWith(answers: Submission.AnswersToQuestions): Submission = {
      (
        Submission.addStatusHistory(Submission.Status.Answering(DateTimeUtils.now, true)) andThen
        Submission.updateLatestAnswersTo(answers)
      )(submission)
    }

    def hasCompletelyAnswered: Submission = {
      Submission.addStatusHistory(Submission.Status.Answering(DateTimeUtils.now, true))(submission)
    }
    
    def answeringWith(answers: Submission.AnswersToQuestions): Submission = {
      (
        Submission.addStatusHistory(Submission.Status.Answering(DateTimeUtils.now, false)) andThen
        Submission.updateLatestAnswersTo(answers)
      )(submission)
    }

    def answering: Submission = {
      Submission.addStatusHistory(Submission.Status.Answering(DateTimeUtils.now, false))(submission)
    }
    
    def submitted: Submission = {
      Submission.submit(DateTimeUtils.now, "bob@example.com")(submission)
    }
  }
}

trait ProgressTestDataHelper {
  
    implicit class ProgressSyntax(submission: Submission) {
      private val allQuestionnaireIds: NonEmptyList[QuestionnaireId] = submission.allQuestionnaires.map(_.id)
      private val allQuestionIds = submission.allQuestions.map(_.id)
      private def questionnaire(qId: QuestionnaireId): Questionnaire = submission.allQuestionnaires.find(q => q.id == qId).get
      private def allQuestionIds(qId: QuestionnaireId) = questionnaire(qId).questions.map(_.question).map(_.id).toList

      private def incompleteQuestionnaireProgress(qId: QuestionnaireId): QuestionnaireProgress = QuestionnaireProgress(QuestionnaireState.InProgress, allQuestionIds(qId))
      private def completedQuestionnaireProgress(qId: QuestionnaireId): QuestionnaireProgress = QuestionnaireProgress(QuestionnaireState.Completed, allQuestionIds.toList)
      private def notStartedQuestionnaireProgress(qId: QuestionnaireId): QuestionnaireProgress = QuestionnaireProgress(QuestionnaireState.NotStarted, allQuestionIds.toList)
      private def notApplicableQuestionnaireProgress(qId: QuestionnaireId): QuestionnaireProgress = QuestionnaireProgress(QuestionnaireState.NotApplicable, allQuestionIds.toList)

      def withIncompleteProgress(): ExtendedSubmission =
        ExtendedSubmission(submission, allQuestionnaireIds.map(i => (i -> incompleteQuestionnaireProgress(i))).toList.toMap)
        
      def withCompletedProgresss(): ExtendedSubmission =
        ExtendedSubmission(submission, allQuestionnaireIds.map(i => (i -> completedQuestionnaireProgress(i))).toList.toMap)

      def withNotStartedProgresss(): ExtendedSubmission =
        ExtendedSubmission(submission, allQuestionnaireIds.map(i => (i -> notStartedQuestionnaireProgress(i))).toList.toMap)

      def withNotApplicableProgresss(): ExtendedSubmission =
        ExtendedSubmission(submission, allQuestionnaireIds.map(i => (i -> notApplicableQuestionnaireProgress(i))).toList.toMap)
    }
}
trait SubmissionsTestData extends QuestionBuilder with QuestionnaireTestData2 with ProgressTestDataHelper with StatusTestDataHelper {

  val submissionId = Submission.Id.random
  val applicationId = ApplicationId.random
  
  val now = DateTimeUtils.now

  val aSubmission = Submission.create("bob@example.com", submissionId, applicationId, now, testGroups, testQuestionIdsOfInterest)

  val altSubmissionId = Submission.Id.random
  require(altSubmissionId != submissionId)
  val altSubmission = Submission.create("bob@example.com", altSubmissionId, applicationId, now.plusSeconds(100), testGroups, testQuestionIdsOfInterest)

  val completedSubmissionId = Submission.Id.random
  require(completedSubmissionId != submissionId)

  val completelyAnswerExtendedSubmission = 
      aSubmission.copy(id = completedSubmissionId)
      .hasCompletelyAnsweredWith(answersToQuestions)
      .withCompletedProgresss


  val createdSubmission = aSubmission
  val answeringSubmission = createdSubmission.answeringWith(answersToQuestions)
  val answeredSubmission = createdSubmission.hasCompletelyAnsweredWith(answersToQuestions)
  val submittedSubmission = Submission.submit(now, "bob@example.com")(answeredSubmission)


  def buildSubmissionWithQuestions(): Submission = {
    val subId = Submission.Id.random
    val appId = ApplicationId.random

    val question1 = yesNoQuestion(1)
    val questionRIName = textQuestion(2)
    val questionRIEmail = textQuestion(3)
    val questionName = textQuestion(4)
    val questionPrivacy = textQuestion(5)
    val questionTerms = textQuestion(6)
    val questionWeb = textQuestion(7)
    val question2 = acknowledgementOnly(8)
    val question3 = multichoiceQuestion(9, "a", "b", "c")
    
    val questionnaire1 = Questionnaire(
        id = QuestionnaireId.random,
        label = Label("Questionnaire 1"),
        questions = NonEmptyList.of(
          QuestionItem(question1), 
          QuestionItem(question2), 
          QuestionItem(question3), 
          QuestionItem(questionName), 
          QuestionItem(questionPrivacy), 
          QuestionItem(questionTerms),
          QuestionItem(questionWeb)
        )
      )

    val questionnaireGroups = NonEmptyList.of(
        GroupOfQuestionnaires(
          heading = "Group 1",
          links = NonEmptyList.of(
            questionnaire1
          )            
        )
    )

    Submission.create("bob@example.com", subId, appId, DateTimeUtils.now, questionnaireGroups, QuestionIdsOfInterest(questionName.id, questionPrivacy.id, questionTerms.id, questionWeb.id, questionRIName.id, questionRIEmail.id))
  }

  private def buildAnsweredSubmission(fullyAnswered: Boolean)(submission: Submission): Submission = {

    def passAnswer(question: Question): ActualAnswer = {
      question match {
        case TextQuestion(id, wording, statement, absence) => TextAnswer("some random text")
        case ChooseOneOfQuestion(id, wording, statement, marking, absence) => SingleChoiceAnswer(marking.filter { case (pa, Pass) => true; case _ => false }.head._1.value)
        case MultiChoiceQuestion(id, wording, statement, marking, absence) => MultipleChoiceAnswer(Set(marking.filter { case (pa, Pass) => true; case _ => false }.head._1.value))
        case AcknowledgementOnly(id, wording, statement) => NoAnswer
        case YesNoQuestion(id, wording, statement, yesMarking, noMarking, absence) => if(yesMarking == Pass) SingleChoiceAnswer("Yes") else SingleChoiceAnswer("No")
      }
    }
    
    val answerQuestions = submission.allQuestions.toList.drop(if(fullyAnswered) 0 else 1)
    val answers = answerQuestions.map(q => (q.id -> passAnswer(q))).toMap

    if(fullyAnswered) {
      submission.hasCompletelyAnsweredWith(answers)
    } else {
      submission.answeringWith(answers)
    }
  }

  def buildPartiallyAnsweredSubmission(submission: Submission = buildSubmissionWithQuestions()): Submission = 
    buildAnsweredSubmission(false)(submission)

  def buildFullyAnsweredSubmission(submission: Submission = buildSubmissionWithQuestions()): Submission =
    buildAnsweredSubmission(true)(submission)


  def allFirstQuestions(questionnaires: NonEmptyList[Questionnaire]): Map[QuestionnaireId, QuestionId] =
    questionnaires.map { qn =>
        (qn.id, qn.questions.head.question.id)
    }
    .toList
    .toMap
  
  val simpleContext = Map(Keys.IN_HOUSE_SOFTWARE -> "Yes", Keys.VAT_OR_ITSA -> "No")
  val soldContext = Map(Keys.IN_HOUSE_SOFTWARE -> "No", Keys.VAT_OR_ITSA -> "No")
  val vatContext = Map(Keys.IN_HOUSE_SOFTWARE -> "Yes", Keys.VAT_OR_ITSA -> "Yes")
}
