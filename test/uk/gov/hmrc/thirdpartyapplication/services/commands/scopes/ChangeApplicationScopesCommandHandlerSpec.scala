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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeApplicationScopes
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ApplicationScopesChanged
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{ScopeAdded, ScopeRemoved}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec
import uk.gov.hmrc.thirdpartyapplication.services.commands.scopes.ChangeApplicationScopesCommandHandler

class ChangeApplicationScopesCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends ApplicationRepositoryMockModule
      with AuditServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appAdminUserId = UserId.random
    val appAdminEmail  = "bob@example.com".toLaxEmail
    val appAdminName   = "Bob"

    val oldScopes   = Set("scope01", "scope02", "scope03")
    val privAccess  = Access.Privileged(scopes = oldScopes)
    val privApp     = anApplicationData.copy(access = privAccess)
    val standardApp = anApplicationData

    val ts = FixedClock.instant

    val underTest = new ChangeApplicationScopesCommandHandler(
      ApplicationRepoMock.aMock,
      AuditServiceMock.aMock,
      FixedClock.clock
    )
  }

  "process" should {
    "create correct event for a valid request" in new Setup {

      ApplicationRepoMock.Save.thenAnswer()
      AuditServiceMock.Audit.thenReturnSuccess()

      val newScopes = Set("scope03", "scope04")
      val result    = await(underTest.process(privApp, ChangeApplicationScopes(gkUserEmail, newScopes, ts)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: ApplicationScopesChanged =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gkUserEmail)
            event.newScopes shouldBe newScopes
            event.oldScopes shouldBe oldScopes
        }
      }

      ApplicationRepoMock.Save.verifyCalled()
      AuditServiceMock.Audit.verifyCalledWith(ScopeRemoved, Map("removedScope" -> "scope01"), hc)
      AuditServiceMock.Audit.verifyCalledWith(ScopeRemoved, Map("removedScope" -> "scope02"), hc)
      AuditServiceMock.Audit.verifyCalledWith(ScopeAdded, Map("newScope" -> "scope04"), hc)
    }

    "return an error if the application is standard access" in new Setup {

      val newScopes = Set("scope03", "scope04")
      checkFailsWith("App must have a PRIVILEGED or ROPC access type") {
        underTest.process(standardApp, ChangeApplicationScopes(gkUserEmail, newScopes, ts))
      }
    }
  }
}
