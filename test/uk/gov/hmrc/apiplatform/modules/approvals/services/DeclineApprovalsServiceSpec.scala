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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.ExtendedSubmission
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.QuestionnaireProgress
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.QuestionnaireState
import cats.data.NonEmptyList
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.time.DateTimeUtils

class DeclineApprovalsServiceSpec extends AsyncHmrcSpec {
  trait Setup extends AuditServiceMockModule 
    with ApplicationRepositoryMockModule 
    with StateHistoryRepositoryMockModule 
    with SubmissionsServiceMockModule
    with ApplicationTestData 
    with SubmissionsTestData {

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId = ApplicationId.random
    val application = anApplicationData(appId, pendingGatekeeperApprovalState("bob"))
    val answeredSubmission = buildAnsweredSubmission()
    val answeredSubmissionWithCreatedState = answeredSubmission.copy(instances = NonEmptyList.of(answeredSubmission.latestInstance.copy(statusHistory = NonEmptyList.of(Submission.Status.Created(DateTimeUtils.now, "user2")))))
    val completedQuestionnaireProgress = QuestionnaireProgress(QuestionnaireState.Completed, answeredSubmission.allQuestionnaires.flatMap(_.questions).map(_.question.id).toList)
    val incompleteProgress = QuestionnaireProgress(QuestionnaireState.InProgress, answeredSubmission.allQuestionnaires.flatMap(_.questions).map(_.question.id).toList)
    val answeredExtendedSubmission = ExtendedSubmission(answeredSubmission, Map((answeredSubmission.allQuestionnaires.head.id -> completedQuestionnaireProgress)))
    val incompleteExtendedSubmission = ExtendedSubmission(answeredSubmission, Map((answeredSubmission.allQuestionnaires.head.id -> incompleteProgress)))
    val createdExtendedSubmission = ExtendedSubmission(answeredSubmissionWithCreatedState, Map((answeredSubmission.allQuestionnaires.head.id -> completedQuestionnaireProgress)))
    val name = "name"
    val reasons = "reasons"
    val underTest = new DeclineApprovalsService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, SubmissionsServiceMock.aMock)
  }

  "DeclineApprovalsService" should {
    "decline the specified application" in new Setup {
      import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
      SubmissionsServiceMock.FetchLatest.thenReturn(answeredExtendedSubmission)
      ApplicationRepoMock.Save.thenReturn(application)
      StateHistoryRepoMock.Insert.thenAnswer()
      SubmissionsServiceMock.Store.thenReturn()
      AuditServiceMock.Audit.thenReturnSuccess()

      val result = await(underTest.decline(application, answeredExtendedSubmission, name, reasons))

      result shouldBe DeclineApprovalsService.Actioned(application)
      ApplicationRepoMock.Save.verifyCalled().state.name shouldBe TESTING
    }

    "fail to decline the specified application if the application is in the incorrect state" in new Setup {
      val result = await(underTest.decline(anApplicationData(appId, testingState()), extendedSubmission, name, reasons))

      result shouldBe DeclineApprovalsService.RejectedDueToIncorrectApplicationState
    }


    "fail to decline the specified application if the submission is in the wrong state" in new Setup {
      val result = await(underTest.decline(application, incompleteExtendedSubmission, name, reasons))

      result shouldBe DeclineApprovalsService.RejectedDueToIncompleteSubmission
    }
  
    "fail to decline the specified application if the submission is in the submission state" in new Setup {
      val result = await(underTest.decline(application, createdExtendedSubmission, name, reasons))

      result shouldBe DeclineApprovalsService.RejectedDueToIncorrectSubmissionState
    }
  }
}