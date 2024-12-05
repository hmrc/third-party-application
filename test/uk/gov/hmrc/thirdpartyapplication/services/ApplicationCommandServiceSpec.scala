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

package uk.gov.hmrc.thirdpartyapplication.controllers

import scala.concurrent.ExecutionContext.Implicits.global

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApiIdentifierFixtures}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaboratorsFixtures
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UnsubscribeFromRetiredApi
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationCommandAuthenticatorMockModule, ApplicationCommandDispatcherMockModule}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandService
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationCommandServiceSpec
    extends AsyncHmrcSpec
    with ApplicationWithCollaboratorsFixtures
    with ApiIdentifierFixtures
    with StoredApplicationFixtures
    with FixedClock {

  trait Setup
      extends ApplicationCommandDispatcherMockModule with ApplicationCommandAuthenticatorMockModule {

    implicit val headers: HeaderCarrier = HeaderCarrier()

    val command = UnsubscribeFromRetiredApi(Actors.Process("Api Publisher"), apiIdentifierOne, instant)

    lazy val underTest = new ApplicationCommandService(
      ApplicationCommandDispatcherMock.aMock,
      ApplicationCommandAuthenticatorMock.aMock
    )
  }

  "ApplicationCommandService" when {
    "authenticateAndDispatch" should {
      "returns result of dispatch call when ApplicationCommandAuthenticator returns true" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        ApplicationCommandDispatcherMock.Dispatch.succeedsWith(storedApp)

        val result = await(underTest.authenticateAndDispatch(applicationIdOne, command, Set.empty).value)
        result shouldBe Right((storedApp, ApplicationCommandDispatcherMock.mockEvents))
        ApplicationCommandDispatcherMock.Dispatch.verifyCalledWith(applicationIdOne, command)
      }

      "returns InsufficentPriviledges Error when ApplicationCommandAuthenticator returns false" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.fails()

        val result = await(underTest.authenticateAndDispatch(applicationIdOne, command, Set.empty).value)
        result shouldBe Left(NonEmptyList.one(CommandFailures.InsufficientPrivileges("Not authenticated")))
        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
      }
    }
  }
}
