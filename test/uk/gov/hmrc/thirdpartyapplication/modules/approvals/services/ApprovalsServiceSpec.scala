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
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import scala.concurrent.Future.{successful, failed}
import uk.gov.hmrc.thirdpartyapplication.models.ValidName
import uk.gov.hmrc.thirdpartyapplication.models.DuplicateName
import uk.gov.hmrc.thirdpartyapplication.models.InvalidName

class ApprovalsServiceSpec extends AsyncHmrcSpec {

  trait Setup 
    extends AuditServiceMockModule 
    with ApplicationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with SubmissionsServiceMockModule
    with SubmissionsTestData
    with ApplicationTestData {

    val requestedByEmailAddress = "email@example.com"
    val application: ApplicationData = anApplicationData(applicationId, testingState())

    val mockApprovalsNamingService = mock[ApprovalsNamingService]

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val underTest = new ApprovalsService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, mockApprovalsNamingService, SubmissionsServiceMock.aMock)
  }

  "ApplicationApprovalsService" when {
    "requestApproval" should {

      "update state, save and audit" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(Some(completedExtendedSubmission))
        when(mockApprovalsNamingService.validateApplicationNameAndAudit(*, *[ApplicationId], *)(*)).thenReturn(successful(ValidName))
        ApplicationRepoMock.Save.thenReturn(application)
        StateHistoryRepoMock.Insert.thenAnswer()
        AuditServiceMock.Audit.thenReturnSuccess()

        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApprovalsService.ApprovalAccepted
        StateHistoryRepoMock.Insert.verifyCalled()
        AuditServiceMock.Audit.verifyCalled()
        ApplicationRepoMock.Save.verifyCalled()
      }

      "return duplicate application name if duplicate" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(Some(completedExtendedSubmission))
        when(mockApprovalsNamingService.validateApplicationNameAndAudit(*, *[ApplicationId], *)(*)).thenReturn(successful(DuplicateName))

        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApprovalsService.ApprovalRejectedDueToDuplicateName(expectedAppName)
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return illegal application name if deny-listed name" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(Some(completedExtendedSubmission))
        when(mockApprovalsNamingService.validateApplicationNameAndAudit(*, *[ApplicationId], *)(*)).thenReturn(successful(InvalidName))

        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApprovalsService.ApprovalRejectedDueToIllegalName(expectedAppName)
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return incomplete for an incomplete submission" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(Some(extendedSubmission))

        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApprovalsService.ApprovalRejectedDueToIncompleteSubmission
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return application not found for an application not found with given appId" in new Setup {
        ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
        
        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApprovalsService.ApprovalRejectedDueNoSuchApplication
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return application in incorrect state an application not in TESTING" in new Setup {
        val prodApplication: ApplicationData = anApplicationData(applicationId, productionState(requestedByEmailAddress))
        ApplicationRepoMock.Fetch.thenReturn(prodApplication)
        
        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApprovalsService.ApprovalRejectedDueToIncorrectState
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return submission not found for an submission not found" in new Setup {
        ApplicationRepoMock.Fetch.thenReturn(application)
        SubmissionsServiceMock.FetchLatest.thenReturn(None)
        
        val result = await(underTest.requestApproval(applicationId, requestedByEmailAddress))

        result shouldBe ApprovalsService.ApprovalRejectedDueNoSuchSubmission
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
      }
    }
  }
}