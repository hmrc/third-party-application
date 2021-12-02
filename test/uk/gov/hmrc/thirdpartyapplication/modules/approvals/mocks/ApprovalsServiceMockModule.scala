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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.modules.approvals.services.ApprovalsService
import uk.gov.hmrc.thirdpartyapplication.modules.approvals.services.ApprovalsService._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

trait ApprovalsServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseApprovalsServiceMock {
    def aMock: ApprovalsService

    object RequestApproval {
      def thenRequestIsApprovedFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalAccepted))
      
      def thenRequestFailsWithInvalidStateTransitionErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToIncorrectState))
      
      def thenRequestFailsWithApplicationAlreadyExistsErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToDuplicateName("my app")))

      def thenRequestFailsWithApplicationDoesNotExistErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueNoSuchApplication))

      def thenRequestFailsWithIncompleteSubmissionErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToIncompleteSubmission))

      def thenRequestFailsWithIllegalNameErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(eqTo(applicationId), eqTo(emailAddress))(*)).thenReturn(successful(ApprovalRejectedDueToIllegalName("my app")))
    }
  }
  
  object ApprovalsServiceMock extends BaseApprovalsServiceMock {
    val aMock = mock[ApprovalsService]
  }

}
