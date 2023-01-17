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

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.updateLatestAnswersTo
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{SingleChoiceAnswer, Submission, TextAnswer}
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec

class SubmissionDataExtracterSpec extends HmrcSpec {

  trait Setup extends SubmissionsTestData {
    import Submission._
    val appName                                           = "expected app name"
    val answersWithAppName: Submission.AnswersToQuestions = Map(QuestionnaireDAO.questionIdsOfInterest.applicationNameId -> TextAnswer(appName))
    val submissionWithAnswers                             = updateLatestAnswersTo(answersWithAppName)(answeringSubmission)
  }

  "SubmissionDataExtracter" when {
    "getApplicationName is called" should {
      "return application name" in new Setup {
        val actualAppName: Option[String] = SubmissionDataExtracter.getApplicationName(submissionWithAnswers)
        actualAppName shouldBe Some(appName)
      }

      "return None if answer not found" in new Setup {
        val actualAppName: Option[String] = SubmissionDataExtracter.getApplicationName(aSubmission)
        actualAppName shouldBe None
      }
    }

    "isRequesterTheResponsibleIndividual" should {
      "return true if the requester is the Responsible Individual" in new Setup {
        val answers: Submission.AnswersToQuestions = Map(QuestionnaireDAO.questionIdsOfInterest.responsibleIndividualIsRequesterId -> SingleChoiceAnswer("Yes"))
        val submission                             = updateLatestAnswersTo(answers)(answeringSubmission)

        SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission) shouldBe true
      }
      "return false if the requester is not the Responsible Individual" in new Setup {
        val answers: Submission.AnswersToQuestions = Map(QuestionnaireDAO.questionIdsOfInterest.responsibleIndividualIsRequesterId -> SingleChoiceAnswer("No"))
        val submission                             = updateLatestAnswersTo(answers)(answeringSubmission)
        SubmissionDataExtracter.isRequesterTheResponsibleIndividual(submission) shouldBe false
      }
    }
  }
}
