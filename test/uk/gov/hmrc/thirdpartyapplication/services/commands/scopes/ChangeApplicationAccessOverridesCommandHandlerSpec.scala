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

package uk.gov.hmrc.thirdpartyapplication.services.commands.scopes

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, OverrideFlag}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeApplicationAccessOverrides
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ApplicationAccessOverridesChanged
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{OverrideAdded, OverrideRemoved}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class ChangeApplicationAccessOverridesCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule
      with AuditServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val oldOverrides: Set[OverrideFlag] = Set(OverrideFlag.PersistLogin, OverrideFlag.OriginOverride("origin01"))
    val stdAccess                       = Access.Standard(overrides = oldOverrides)
    val standardApp                     = anApplicationData().copy(access = stdAccess)
    val privApp                         = anApplicationData().copy(access = Access.Privileged())

    val ts = FixedClock.instant

    val underTest = new ChangeApplicationAccessOverridesCommandHandler(
      ApplicationRepoMock.aMock,
      AuditServiceMock.aMock,
      FixedClock.clock
    )
  }

  "process" should {
    "create correct event for a valid request" in new Setup {

      ApplicationRepoMock.Save.thenAnswer()
      AuditServiceMock.Audit.thenReturnSuccess()

      val newOverrides: Set[OverrideFlag] = Set(OverrideFlag.PersistLogin, OverrideFlag.SuppressIvForIndividuals(Set("scope01, scope02")))
      val result                          = await(underTest.process(standardApp, ChangeApplicationAccessOverrides(gkUserEmail, newOverrides, ts)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: ApplicationAccessOverridesChanged =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gkUserEmail)
            event.newOverrides shouldBe newOverrides
            event.oldOverrides shouldBe oldOverrides
        }
      }

      ApplicationRepoMock.Save.verifyCalled()
      AuditServiceMock.Audit.verifyCalledWith(OverrideRemoved, Map("removedOverride" -> "ORIGIN_OVERRIDE"), hc)
      AuditServiceMock.Audit.verifyCalledWith(OverrideAdded, Map("newOverride" -> "SUPPRESS_IV_FOR_INDIVIDUALS"), hc)
    }

    "return an error if the application is not standard access" in new Setup {

      val newOverrides: Set[OverrideFlag] = Set(OverrideFlag.SuppressIvForIndividuals(Set("scope01, scope02")))
      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(privApp, ChangeApplicationAccessOverrides(gkUserEmail, newOverrides, ts))
      }
    }
  }
}
