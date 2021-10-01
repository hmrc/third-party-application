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

import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AnswerQuestion
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.thirdpartyapplication.domain.models._

trait TestData {
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
    val answers = AnswerQuestion.createMapFor(allQuestionnaires)
    val submission = Submission(submissionId, applicationId, DateTimeUtils.now, groups, AnswerQuestion.createMapFor(allQuestionnaires))
    
    val altSubmissionId = SubmissionId.random
    require(altSubmissionId != submissionId)
    val altSubmission = Submission(altSubmissionId, applicationId, DateTimeUtils.now.plusMillis(100), groups, AnswerQuestion.createMapFor(allQuestionnaires))

}
