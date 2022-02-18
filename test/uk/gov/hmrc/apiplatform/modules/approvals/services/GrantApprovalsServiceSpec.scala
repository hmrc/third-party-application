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
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.ActualAnswersAsText
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.QuestionsAndAnswersToMap

class GrantApprovalsServiceSpec extends AsyncHmrcSpec {
  trait Setup extends AuditServiceMockModule 
    with ApplicationRepositoryMockModule 
    with StateHistoryRepositoryMockModule 
    with SubmissionsServiceMockModule
    with EmailConnectorMockModule
    with ApplicationTestData 
    with SubmissionsTestData {

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationPendingGKApproval = anApplicationData(applicationId, pendingGatekeeperApprovalState("bob"))
    val underTest = new GrantApprovalsService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, SubmissionsServiceMock.aMock, EmailConnectorMock.aMock)
  }

  "GrantApprovalsService" should {
    "grant the specified application" in new Setup {
      import uk.gov.hmrc.thirdpartyapplication.domain.models.State._

      ApplicationRepoMock.Save.thenAnswer()
      StateHistoryRepoMock.Insert.thenAnswer()
      SubmissionsServiceMock.Store.thenReturn()
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()
      EmailConnectorMock.SendApplicationApprovedAdminConfirmation.thenReturnSuccess()

      val result = await(underTest.grant(applicationPendingGKApproval, submittedSubmission, gatekeeperUserName))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) if(app.state.name == PENDING_REQUESTER_VERIFICATION) =>
      }
      ApplicationRepoMock.Save.verifyCalled().state.name shouldBe PENDING_REQUESTER_VERIFICATION
      ApplicationRepoMock.Save.verifyCalled().state.name shouldBe PENDING_REQUESTER_VERIFICATION
      SubmissionsServiceMock.Store.verifyCalledWith().status.isGranted shouldBe true
      SubmissionsServiceMock.Store.verifyCalledWith().status should matchPattern {
        case Submission.Status.Granted(_, gatekeeperUserName) =>
      }

      val (someQuestionId, expectedAnswer) = submittedSubmission.latestInstance.answersToQuestions.head
      val someQuestionWording = QuestionsAndAnswersToMap.stripSpacesAndCapitalise(submittedSubmission.findQuestion(someQuestionId).get.wording.value)

      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserName
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationApprovalGranted
      AuditServiceMock.AuditGatekeeperAction.verifyExtras().get(someQuestionWording).value shouldBe ActualAnswersAsText(expectedAnswer)
    }

    "fail to grant the specified application if the application is in the incorrect state" in new Setup {
      val result = await(underTest.grant(anApplicationData(applicationId, testingState()), answeredSubmission, gatekeeperUserName))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }

    "fail to grant the specified application if the submission is not in the submitted state" in new Setup {
      val result = await(underTest.grant(applicationPendingGKApproval, answeredSubmission, gatekeeperUserName))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectSubmissionState
    }
  }
}