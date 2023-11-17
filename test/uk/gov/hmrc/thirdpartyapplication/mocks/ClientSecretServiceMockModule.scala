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

package uk.gov.hmrc.thirdpartyapplication.mocks

import scala.concurrent.Future

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredClientSecret
import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService

trait ClientSecretServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  object ClientSecretServiceMock {
    lazy val aMock: ClientSecretService = mock[ClientSecretService]

    def verify                                                  = MockitoSugar.verify(aMock)
    def verify(mode: org.mockito.verification.VerificationMode) = MockitoSugar.verify(aMock, mode)

    object ClientSecretIsValid {

      def thenReturnValidationResult(applicationId: ApplicationId, secret: String, candidateClientSecrets: Seq[StoredClientSecret])(matchingClientSecret: StoredClientSecret) =
        when(aMock.clientSecretIsValid(eqTo(applicationId), eqTo(secret), eqTo(candidateClientSecrets))).thenReturn(Future.successful(Some(matchingClientSecret)))

      def noMatchingClientSecret(applicationId: ApplicationId, secret: String, candidateClientSecrets: Seq[StoredClientSecret]) =
        when(aMock.clientSecretIsValid(eqTo(applicationId), eqTo(secret), eqTo(candidateClientSecrets))).thenReturn(Future.successful(None))
    }
  }

}
