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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.QuestionnaireState._
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec

class ExtendedSubmissionSpec extends HmrcSpec {

  private def buildExtendedSubmissionWithStates(states: QuestionnaireState*): ExtendedSubmission = {
    val submission = mock[Submission]
    val progress = states.toList.map(s => QuestionnaireId.random -> QuestionnaireProgress(s, List.empty)).toMap
    ExtendedSubmission(submission, progress)
  }

  trait Setup {
    val submissionWithAllComplete = buildExtendedSubmissionWithStates(Completed, Completed, Completed)
    val submissionWithNoQuestionnaires = buildExtendedSubmissionWithStates()
    val submissionWithOneNotStarted = buildExtendedSubmissionWithStates(Completed, NotStarted, Completed)
    val submissionWithOneInProgress = buildExtendedSubmissionWithStates(Completed, InProgress, Completed)
    val submissionWithCompletedAndNotApplicable = buildExtendedSubmissionWithStates(NotApplicable, Completed, NotApplicable, Completed)
  }

  "ExtendedSubmission.isCompleted" should {
    "be true if no questionnnaires" in new Setup {
      submissionWithAllComplete.isCompleted shouldBe true
    }
    "be true if all Completed" in new Setup {
      submissionWithAllComplete.isCompleted shouldBe true
    }
    "be true if mixture of Completed and Not Applicable" in new Setup {
      submissionWithCompletedAndNotApplicable.isCompleted shouldBe true
    }
    "be false if any Not Started" in new Setup {
      submissionWithOneNotStarted.isCompleted shouldBe false
    }
    "be false if any In Progress" in new Setup {
      submissionWithOneInProgress.isCompleted shouldBe false
    }
  }

}