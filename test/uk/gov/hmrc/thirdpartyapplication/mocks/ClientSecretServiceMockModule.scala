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

package uk.gov.hmrc.thirdpartyapplication.mocks

import java.util.UUID

import com.github.t3hnar.bcrypt._
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService

import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

trait ClientSecretServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  object ClientSecretServiceMock {
    lazy val aMock: ClientSecretService =  mock[ClientSecretService]

    def verify = MockitoSugar.verify(aMock)
    def verify(mode: org.mockito.verification.VerificationMode) = MockitoSugar.verify(aMock,mode)

    object GenerateClientSecret {
      def thenReturnWithSpecificSecret(id: String, secret: String) =
        when(aMock.generateClientSecret()).thenReturn( (ClientSecret(id = id, name = secret.takeRight(4), hashedSecret = secret.bcrypt(4)), secret) )

      def thenReturnWithRandomSecret() = {
        val secret = UUID.randomUUID().toString
        when(aMock.generateClientSecret()).thenReturn((ClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4)), secret))
      }
    }

    object ClientSecretIsValid {
      def thenReturnValidationResult(applicationId: ApplicationId, secret: String, candidateClientSecrets: Seq[ClientSecret])(matchingClientSecret: ClientSecret) =
        when(aMock.clientSecretIsValid(eqTo(applicationId), eqTo(secret), eqTo(candidateClientSecrets))).thenReturn(Future.successful(Some(matchingClientSecret)))

      def noMatchingClientSecret(applicationId: ApplicationId, secret: String, candidateClientSecrets: Seq[ClientSecret]) =
        when(aMock.clientSecretIsValid(eqTo(applicationId), eqTo(secret), eqTo(candidateClientSecrets))).thenReturn(Future.successful(None))
    }
  }

}
