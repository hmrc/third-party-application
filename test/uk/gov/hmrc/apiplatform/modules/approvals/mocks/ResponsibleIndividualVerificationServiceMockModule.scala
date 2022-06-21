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
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId, ResponsibleIndividualVerificationWithDetails}
import uk.gov.hmrc.apiplatform.modules.approvals.services.ResponsibleIndividualVerificationService
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, ResponsibleIndividual}

import java.time.{Clock, LocalDateTime}
import scala.concurrent.Future

trait ResponsibleIndividualVerificationServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseResponsibleIndividualVerificationServiceMock {
    def aMock: ResponsibleIndividualVerificationService

    def verifyZeroInteractions(): Unit = MockitoSugar.verifyZeroInteractions(aMock)

    object CreateNewVerification {

      def thenCreateNewVerification(verificationId: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random) = {
        when(aMock.createNewVerification(*[ApplicationData], *[Submission.Id], *)).thenAnswer((appData: ApplicationData, submissionId: Submission.Id, index: Int) =>
          Future.successful(
            ResponsibleIndividualVerification(verificationId, appData.id, submissionId, index, appData.name, LocalDateTime.now(Clock.systemUTC()))
          )
        )
      }
    }

    object GetVerification {

      def thenGetVerification(code: String) = {
        when(aMock.getVerification(*)).thenAnswer((code: String) =>
          Future.successful(Some(
            ResponsibleIndividualVerification(
              ResponsibleIndividualVerificationId(code),
              ApplicationId.random,
              Submission.Id.random,
              0,
              "App name",
              LocalDateTime.now(Clock.systemUTC())
            )
          ))
        )
      }

      def thenReturnNone() = {
        when(aMock.getVerification(*)).thenAnswer(Future.successful(None))
      }
    }

    object Accept {

      def thenAccept() = {
        when(aMock.accept(*)).thenAnswer((code: String) =>
          Future.successful(Right(
            ResponsibleIndividualVerificationWithDetails(
              ResponsibleIndividualVerification(
                ResponsibleIndividualVerificationId(code),
                ApplicationId.random,
                Submission.Id.random,
                0,
                "App name",
                LocalDateTime.now(Clock.systemUTC())
              ),
              ResponsibleIndividual.build("bob example", "bob@example.com"),
              "Rick Deckard",
              "rick@submitter.com"
            )
          ))
        )
      }
    }

    object Decline {

      def thenDecline() = {
        when(aMock.decline(*)).thenAnswer((code: String) =>
          Future.successful(Right(
            ResponsibleIndividualVerification(
              ResponsibleIndividualVerificationId(code),
              ApplicationId.random,
              Submission.Id.random,
              0,
              "App name",
              LocalDateTime.now(Clock.systemUTC())
            )
          ))
        )
      }
    }
  }

  object ResponsibleIndividualVerificationServiceMock extends BaseResponsibleIndividualVerificationServiceMock {
    val aMock = mock[ResponsibleIndividualVerificationService]
  }
}
