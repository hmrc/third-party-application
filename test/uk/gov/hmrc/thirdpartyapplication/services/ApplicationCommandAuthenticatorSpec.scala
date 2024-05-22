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

package uk.gov.hmrc.thirdpartyapplication.services

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ClientSecret
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideAuthConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class ApplicationCommandAuthenticatorSpec extends AsyncHmrcSpec with StrideAuthConnectorMockModule with FixedClock {

  trait Setup {

    val devEmail         = "dev@example.com".toLaxEmail
    val developerAsActor = Actors.AppCollaborator(devEmail)
    val gatekeeperUser   = "gatekeeper.user"

    val strideAuthRoles: StrideAuthRoles = StrideAuthRoles("admin", "super-user", "user")
    implicit val headers: HeaderCarrier  = HeaderCarrier()

    val underTest = new ApplicationCommandAuthenticator(
      strideAuthRoles,
      StrideAuthConnectorMock.aMock
    )
  }

  "authenticateCommand" when {
    "command doesnt have GatekeeperMixin" should {
      "authorisation is not performed, returns true" in new Setup {
        val cmd: AddClientSecret = AddClientSecret(developerAsActor, "name", ClientSecret.Id.random, "hashedSecret", instant)
        val result               = await(underTest.authenticateCommand(cmd))
        result shouldBe true
        StrideAuthConnectorMock.Authorise.verifyNotCalled
      }
    }

    "command has GatekeeperMixin" should {
      "authorisation is performed and passes, returns true" in new Setup {
        StrideAuthConnectorMock.Authorise.succeeds
        val cmd: ResendRequesterEmailVerification = ResendRequesterEmailVerification(gatekeeperUser, instant)
        val result                                = await(underTest.authenticateCommand(cmd))
        result shouldBe true
        StrideAuthConnectorMock.Authorise.verifyCalledOnce
      }

      "authorisation is performed and fails, returns false" in new Setup {
        StrideAuthConnectorMock.Authorise.fails
        val cmd: ResendRequesterEmailVerification = ResendRequesterEmailVerification(gatekeeperUser, instant)
        val result                                = await(underTest.authenticateCommand(cmd))
        result shouldBe false
      }
    }
  }
}
