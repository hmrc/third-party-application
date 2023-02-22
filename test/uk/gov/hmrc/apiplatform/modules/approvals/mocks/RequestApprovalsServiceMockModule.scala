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

package uk.gov.hmrc.apiplatform.modules.approvals.mocks

import scala.concurrent.Future.successful

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.approvals.services.RequestApprovalsService
import uk.gov.hmrc.apiplatform.modules.approvals.services.RequestApprovalsService._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, FixedClock}

trait RequestApprovalsServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {

  protected trait BaseRequestApprovalsServiceMock {
    def aMock: RequestApprovalsService

    object RequestApproval {

      def thenRequestIsApprovedFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(*, *, *, *)(*)).thenReturn(successful(ApprovalAccepted(anApplicationData(applicationId))))

      def thenRequestFailsWithInvalidStateTransitionErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(*, *, *, *)(*)).thenReturn(successful(ApprovalRejectedDueToIncorrectApplicationState))

      def thenRequestFailsWithApplicationNameAlreadyExistsErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(*, *, *, *)(*)).thenReturn(successful(ApprovalRejectedDueToDuplicateName("my app")))

      def thenRequestFailsWithIncorrectSubmissionErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(*, *, *, *)(*)).thenReturn(successful(ApprovalRejectedDueToIncorrectSubmissionState(Submission.Status.Created(FixedClock.now, "Bob@fake.com"))))

      def thenRequestFailsWithIllegalNameErrorFor(applicationId: ApplicationId, emailAddress: String) =
        when(aMock.requestApproval(*, *, *, *)(*)).thenReturn(successful(ApprovalRejectedDueToIllegalName("my app")))
    }
  }

  object RequestApprovalsServiceMock extends BaseRequestApprovalsServiceMock {
    val aMock = mock[RequestApprovalsService]
  }

}
