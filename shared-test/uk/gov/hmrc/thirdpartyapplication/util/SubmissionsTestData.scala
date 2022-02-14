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

package uk.gov.hmrc.thirdpartyapplication.util

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.AskWhen.Context.Keys
import cats.data.NonEmptyList

trait SubmissionsTestData extends QuestionBuilder {
  val questionnaire = QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire
  val questionnaireId = questionnaire.id
  val question = questionnaire.questions.head.question
  val questionId = question.id
  val question2Id = questionnaire.questions.tail.head.question.id
  val questionnaireAlt = QuestionnaireDAO.Questionnaires.ServiceManagementPractices.questionnaire
  val questionnaireAltId = questionnaireAlt.id
  val questionAltId = questionnaireAlt.questions.head.question.id
  val optionalQuestion = QuestionnaireDAO.Questionnaires.CustomersAuthorisingYourSoftware.question4
  val optionalQuestionId = optionalQuestion.id

  val submissionId = Submission.Id.random
  val applicationId = ApplicationId.random

  val groups = QuestionnaireDAO.Questionnaires.activeQuestionnaireGroupings
  val allQuestionnaires = groups.flatMap(_.links)

  def firstQuestion(questionnaire: Questionnaire) = questionnaire.questions.head.question.id

  val initialProgress = QuestionnaireDAO.Questionnaires.allIndividualQuestionnaires.map(q => q.id -> QuestionnaireProgress(QuestionnaireState.NotStarted, List(firstQuestion(q)))).toMap
  val completedProgress = QuestionnaireDAO.Questionnaires.allIndividualQuestionnaires.map(q => q.id -> QuestionnaireProgress(QuestionnaireState.Completed, List(firstQuestion(q)))).toMap

  val initialStatus = Submission.Status.Created(DateTimeUtils.now, "bob@example.com")
  val answeredCompletelyStatus = Submission.Status.Answering(DateTimeUtils.now, true)
  val initialInstances = NonEmptyList.of(Submission.Instance(0, Map.empty, NonEmptyList.of(initialStatus)))
  val aSubmission = Submission(submissionId, applicationId, DateTimeUtils.now, groups, QuestionnaireDAO.questionIdsOfInterest, initialInstances)

  val extendedSubmission = ExtendedSubmission(aSubmission, initialProgress)

  val altSubmissionId = Submission.Id.random
  require(altSubmissionId != submissionId)
  val altSubmission = Submission(altSubmissionId, applicationId, DateTimeUtils.now.plusMillis(100), groups, QuestionnaireDAO.questionIdsOfInterest, initialInstances)

  val completedSubmissionId = Submission.Id.random
  require(completedSubmissionId != submissionId)
  val expectedAppName = "expectedAppName"
  val answersToQuestions: Submission.AnswersToQuestions = 
    Map(
      QuestionnaireDAO.questionIdsOfInterest.applicationNameId -> TextAnswer(expectedAppName), 
      QuestionnaireDAO.questionIdsOfInterest.responsibleIndividualEmailId -> TextAnswer("bob@example.com"),
      QuestionnaireDAO.questionIdsOfInterest.responsibleIndividualNameId -> TextAnswer("Bob Cratchett")
    )  
  val answeredInstances = NonEmptyList.of(Submission.Instance(0, answersToQuestions, NonEmptyList.of(answeredCompletelyStatus, initialStatus)))
  
  val completedSubmission = Submission(completedSubmissionId, applicationId, DateTimeUtils.now.plusMillis(100), groups, QuestionnaireDAO.questionIdsOfInterest, answeredInstances)

  val completedExtendedSubmission = ExtendedSubmission(completedSubmission, completedProgress)

  def buildCompletedSubmissionWithQuestions(): Submission = {
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

    val instances = NonEmptyList.of(Submission.Instance(0, Map.empty, NonEmptyList.of(Submission.Status.Submitted(DateTimeUtils.now, "user1"))))
    
    Submission(subId, appId, DateTimeUtils.now, questionnaireGroups, QuestionIdsOfInterest(questionName.id, questionPrivacy.id, questionTerms.id, questionWeb.id, questionRIName.id, questionRIEmail.id), instances)
  }

  def buildAnsweredSubmission(submission: Submission = buildCompletedSubmissionWithQuestions()): Submission = {

    def passAnswer(question: Question): ActualAnswer = {
      question match {
        case TextQuestion(id, wording, statement, absence) => TextAnswer("some random text")
        case ChooseOneOfQuestion(id, wording, statement, marking, absence) => SingleChoiceAnswer(marking.filter { case (pa, Pass) => true; case _ => false }.head._1.value)
        case MultiChoiceQuestion(id, wording, statement, marking, absence) => MultipleChoiceAnswer(Set(marking.filter { case (pa, Pass) => true; case _ => false }.head._1.value))
        case AcknowledgementOnly(id, wording, statement) => NoAnswer
        case YesNoQuestion(id, wording, statement, yesMarking, noMarking, absence) => if(yesMarking == Pass) SingleChoiceAnswer("Yes") else SingleChoiceAnswer("No")
      }
    }
    
    val allQuestions = submission.allQuestions
    val answers = allQuestions.map(q => (q.id -> passAnswer(q))).toList.toMap
    val latestInstance = submission.latestInstance
    val newLatestInstance = latestInstance.copy(answersToQuestions = answers)

    submission.copy(instances = NonEmptyList(newLatestInstance, submission.instances.tail))
  }

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
