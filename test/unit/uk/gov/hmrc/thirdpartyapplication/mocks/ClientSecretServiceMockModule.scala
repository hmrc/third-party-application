/*
 * Copyright 2020 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.mocks

import java.util.UUID

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService.maskSecret
import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService
import com.github.t3hnar.bcrypt._

import scala.concurrent.Future

trait ClientSecretServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  object ClientSecretServiceMock {
    lazy val aMock: ClientSecretService =  mock[ClientSecretService]

    def verify = MockitoSugar.verify(aMock)
    def verify(mode: org.mockito.verification.VerificationMode) = MockitoSugar.verify(aMock,mode)

    object GenerateClientSecret {
      def thenReturnWithSpecificSecret(secret: String) =
        when(aMock.generateClientSecret()).thenReturn(ClientSecret(maskSecret(secret), secret, hashedSecret = secret.bcrypt))

      def thenReturnWithRandomSecret() = {
        val secret = UUID.randomUUID().toString
        when(aMock.generateClientSecret()).thenReturn(ClientSecret(maskSecret(secret), secret, hashedSecret = secret.bcrypt))
      }
    }

    object ClientSecretIsValid {
      def thenReturnValidationResult(secret: String, candidateClientSecrets: Seq[ClientSecret])(matchingClientSecret: ClientSecret) =
        when(aMock.clientSecretIsValid(secret, candidateClientSecrets)).thenReturn(Future.successful(Some(matchingClientSecret)))

      def noMatchingClientSecret(secret: String, candidateClientSecrets: Seq[ClientSecret]) =
        when(aMock.clientSecretIsValid(secret, candidateClientSecrets)).thenReturn(Future.successful(None))
    }
  }

}
