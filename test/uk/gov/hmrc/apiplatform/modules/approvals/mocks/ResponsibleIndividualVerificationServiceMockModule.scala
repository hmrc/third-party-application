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

import scala.concurrent.Future

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualVerificationId
}
import uk.gov.hmrc.apiplatform.modules.approvals.services.ResponsibleIndividualVerificationService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

trait ResponsibleIndividualVerificationServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with FixedClock {

  protected trait BaseResponsibleIndividualVerificationServiceMock {
    def aMock: ResponsibleIndividualVerificationService

    def verifyZeroInteractions(): Unit = MockitoSugar.verifyZeroInteractions(aMock)

    object CreateNewVerification {

      def thenCreateNewVerification(verificationId: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random) = {
        when(aMock.createNewToUVerification(*[StoredApplication], *[SubmissionId], *)).thenAnswer((appData: StoredApplication, submissionId: SubmissionId, index: Int) =>
          Future.successful(
            ResponsibleIndividualToUVerification(verificationId, appData.id, submissionId, index, appData.name, instant)
          )
        )
      }
    }

    object CreateNewTouUpliftVerification {

      def thenCreateNewTouUpliftVerification(verificationId: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random) = {
        when(aMock.createNewTouUpliftVerification(*[StoredApplication], *[SubmissionId], *, *, *[LaxEmailAddress])).thenAnswer(
          (appData: StoredApplication, submissionId: SubmissionId, index: Int, requesterName: String, requesterEmail: LaxEmailAddress) =>
            Future.successful(
              ResponsibleIndividualTouUpliftVerification(verificationId, appData.id, submissionId, index, appData.name, instant, requesterName, requesterEmail)
            )
        )
      }
    }

    object GetVerification {

      def thenGetVerification(code: String) = {
        when(aMock.getVerification(*)).thenAnswer((code: String) =>
          Future.successful(Some(
            ResponsibleIndividualToUVerification(
              ResponsibleIndividualVerificationId(code),
              ApplicationId.random,
              SubmissionId.random,
              0,
              "App name",
              instant
            )
          ))
        )
      }

      def thenReturnNone() = {
        when(aMock.getVerification(*)).thenAnswer(Future.successful(None))
      }
    }
  }

  object ResponsibleIndividualVerificationServiceMock extends BaseResponsibleIndividualVerificationServiceMock {
    val aMock = mock[ResponsibleIndividualVerificationService]
  }
}
