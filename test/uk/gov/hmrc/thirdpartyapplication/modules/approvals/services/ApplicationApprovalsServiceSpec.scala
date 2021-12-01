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

package uk.gov.hmrc.thirdpartyapplication.modules.approvals.services

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationNamingServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

class ApplicationApprovalsServiceSpec extends AsyncHmrcSpec {

  trait Setup 
    extends AuditServiceMockModule 
    with ApplicationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with ApplicationNamingServiceMockModule
    with SubmissionsServiceMockModule
    with SubmissionsTestData
    with ApplicationTestData {

    val requestedByEmailAddress = "email@example.com"
    val application: ApplicationData = anApplicationData(applicationId, testingState())

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val underTest = new ApplicationApprovalsService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, ApplicationNamingServiceMock.aMock, SubmissionsServiceMock.aMock)
  }

  "ApplicationApprovalsService" when {
    "requestApproval" should {

      "should update state and audit" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(Some(completedExtendedSubmission))
        StateHistoryRepoMock.Insert.thenAnswer()
        AuditServiceMock.Audit.thenReturnSuccess()

        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApplicationApprovalsService.ApprovalAccepted
        StateHistoryRepoMock.Insert.verifyCalled()
        AuditServiceMock.Audit.verifyCalled()
      }

      "return incomplete for an incomplete submission" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(Some(extendedSubmission))

        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApplicationApprovalsService.ApprovalRejectedDueToIncompleteSubmission
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
      }

      "return application not found for an application not found with given appId" in new Setup {
        ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
        
        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApplicationApprovalsService.ApprovalRejectedDueNoSuchApplication
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
      }

      "return application in incorrect state an application not in TESTING" in new Setup {
        val prodApplication: ApplicationData = anApplicationData(applicationId, productionState(requestedByEmailAddress))
        ApplicationRepoMock.Fetch.thenReturn(prodApplication)
        
        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApplicationApprovalsService.ApprovalRejectedDueToIncorrectState
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
      }

      "return submission not found for an submission not found" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(None)
        
        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApplicationApprovalsService.ApprovalRejectedDueNoSuchSubmission
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
      }
    }
  }
}