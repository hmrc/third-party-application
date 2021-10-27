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

package uk.gov.hmrc.thirdpartyapplication.util

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.DeriveContext
import cats.data.NonEmptyList

trait SubmissionsTestData {
  val questionnaire = QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire
  val questionnaireId = questionnaire.id
  val question = questionnaire.questions.head.question
  val questionId = question.id
  val question2Id = questionnaire.questions.tail.head.question.id
  val questionnaireAlt = QuestionnaireDAO.Questionnaires.BusinessDetails.questionnaire
  val questionnaireAltId = questionnaireAlt.id
  val questionAltId = questionnaireAlt.questions.head.question.id


  val submissionId = SubmissionId.random
  val applicationId = ApplicationId.random

  val groups = QuestionnaireDAO.Questionnaires.activeQuestionnaireGroupings
  val allQuestionnaires = groups.flatMap(_.links)

  def firstQuestion(questionnaire: Questionnaire) = questionnaire.questions.head.question.id

  val initialProgress = QuestionnaireDAO.Questionnaires.allIndividualQuestionnaires.map(q => q.id -> QuestionnaireProgress(NotStarted, List(firstQuestion(q)))).toMap

  val submission = Submission(submissionId, applicationId, DateTimeUtils.now, groups, Map.empty, initialProgress)
  
  val altSubmissionId = SubmissionId.random
  require(altSubmissionId != submissionId)
  val altSubmission = Submission(altSubmissionId, applicationId, DateTimeUtils.now.plusMillis(100), groups, Map.empty, initialProgress)

  def allFirstQuestions(questionnaires: NonEmptyList[Questionnaire]): Map[QuestionnaireId, QuestionId] =
    questionnaires.map { qn =>
        (qn.id, qn.questions.head.question.id)
    }
    .toList
    .toMap
  
  val simpleContext = Map(DeriveContext.Keys.IN_HOUSE_SOFTWARE -> "Yes", DeriveContext.Keys.VAT_OR_ITSA -> "No")
  val soldContext = Map(DeriveContext.Keys.IN_HOUSE_SOFTWARE -> "No", DeriveContext.Keys.VAT_OR_ITSA -> "No")
  val vatContext = Map(DeriveContext.Keys.IN_HOUSE_SOFTWARE -> "Yes", DeriveContext.Keys.VAT_OR_ITSA -> "Yes")
}
