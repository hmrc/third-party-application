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
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.ActualAnswersAsText
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.QuestionsAndAnswersToMap

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

    val gatekeeperUserName = "gatekeeperUserName"
    val reasons = "reasons"
    val underTest = new DeclineApprovalsService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, SubmissionsServiceMock.aMock)


  }

  "DeclineApprovalsService" should {
    "decline the specified application" in new Setup {
      import uk.gov.hmrc.thirdpartyapplication.domain.models.State._

      ApplicationRepoMock.Save.thenReturn(application)
      StateHistoryRepoMock.Insert.thenAnswer()
      SubmissionsServiceMock.Store.thenReturn()
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()

      val result = await(underTest.decline(application, submittedSubmission, gatekeeperUserName, reasons))

      result shouldBe DeclineApprovalsService.Actioned(application)
      ApplicationRepoMock.Save.verifyCalled().state.name shouldBe TESTING
      val finalSubmission = SubmissionsServiceMock.Store.verifyCalledWith()
      finalSubmission.status.isAnswering shouldBe true
      finalSubmission.instances.tail.head.statusHistory.head should matchPattern {
        case Submission.Status.Declined(_, gatekeeperUserName, reasons) =>
      }

      val (someQuestionId, expectedAnswer) = submittedSubmission.latestInstance.answersToQuestions.head
      println(submittedSubmission.findQuestion(someQuestionId))
      val someQuestionWording = QuestionsAndAnswersToMap.stripSpacesAndCapitalise(submittedSubmission.findQuestion(someQuestionId).get.wording.value)

      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserName
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationApprovalDeclined
      AuditServiceMock.AuditGatekeeperAction.verifyExtras().get(someQuestionWording).value shouldBe ActualAnswersAsText(expectedAnswer)
    }

    "fail to decline the specified application if the application is in the incorrect state" in new Setup {
      val result = await(underTest.decline(anApplicationData(appId, testingState()), answeredSubmission, gatekeeperUserName, reasons))

      result shouldBe DeclineApprovalsService.RejectedDueToIncorrectApplicationState
    }
 
    "fail to decline the specified application if the submission is not in the submitted state" in new Setup {
      val result = await(underTest.decline(application, answeredSubmission, gatekeeperUserName, reasons))

      result shouldBe DeclineApprovalsService.RejectedDueToIncorrectSubmissionState
    }
  }
}