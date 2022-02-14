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
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.TextAnswer

class SubmissionDataExtracterSpec extends HmrcSpec {
  
  trait Setup extends SubmissionsTestData {
    import Submission._
    val appName = "expected app name"
    val answersWithAppName: Submission.AnswersToQuestions = Map(QuestionnaireDAO.questionIdsOfInterest.applicationNameId -> TextAnswer(appName))
    val submissionWithAnswers = updateLatestAnswersTo(answersWithAppName)(Submission(submissionId, applicationId, DateTimeUtils.now, groups, QuestionnaireDAO.questionIdsOfInterest, initialInstances))
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
  }
}
