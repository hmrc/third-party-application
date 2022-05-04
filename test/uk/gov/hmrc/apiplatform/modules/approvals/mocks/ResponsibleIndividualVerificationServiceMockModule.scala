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

package uk.gov.hmrc.apiplatform.modules.approvals.mocks

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.approvals.services.ResponsibleIndividualVerificationService
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

import scala.concurrent.Future

trait ResponsibleIndividualVerificationServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseResponsibleIndividualVerificationServiceMock {
    def aMock: ResponsibleIndividualVerificationService

    def verifyZeroInteractions(): Unit = MockitoSugar.verifyZeroInteractions(aMock)

    object Verification {
      def thenCreateNewVerification(verificationId: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random) = {
        when(aMock.createNewVerification(*[ApplicationId], *[Submission.Id], *)).thenAnswer(
          (appId: ApplicationId, submissionId: Submission.Id, index: Int) => Future.successful(
            ResponsibleIndividualVerification(verificationId, appId, submissionId, index)
          )
        )
      }
    }
  }

  object ResponsibleIndividualVerificationServiceMock extends BaseResponsibleIndividualVerificationServiceMock {
    val aMock = mock[ResponsibleIndividualVerificationService]
  }
}

