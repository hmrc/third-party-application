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

package uk.gov.hmrc.thirdpartyapplication.modules.approvals.mocks

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.modules.approvals.services.RequestApprovalsService
import uk.gov.hmrc.thirdpartyapplication.modules.approvals.services.RequestApprovalsService._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

trait RequestApprovalsServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {
  protected trait BaseRequestApprovalsServiceMock {
    def aMock: RequestApprovalsService

    object RequestApproval {
      def thenRequestIsApprovedFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalAccepted(anApplicationData(applicationId))))
      
      def thenRequestFailsWithInvalidStateTransitionErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToIncorrectState))
      
      def thenRequestFailsWithApplicationAlreadyExistsErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToDuplicateName("my app")))

      def thenRequestFailsWithApplicationDoesNotExistErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToNoSuchApplication))

      def thenRequestFailsWithIncompleteSubmissionErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToIncompleteSubmission))

      def thenRequestFailsWithIllegalNameErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToIllegalName("my app")))
    }
  }
  
  object RequestApprovalsServiceMock extends BaseRequestApprovalsServiceMock {
    val aMock = mock[RequestApprovalsService]
  }

}