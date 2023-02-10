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

import uk.gov.hmrc.thirdpartyapplication.controllers.{OverridesRequest, OverridesResponse, ScopeRequest, ScopeResponse}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{OverrideAdded, OverrideRemoved, ScopeAdded, ScopeRemoved}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId

class AccessServiceSpec extends AsyncHmrcSpec {

  "Access service update scopes function" should {

    "invoke repository save function with updated privileged application data access scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(privilegedApplicationDataWithScopes(applicationId), Set.empty, scopes1to4)
      AuditServiceMock.Audit.thenReturnSuccess()
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes1to4))(hc))
      ApplicationRepoMock.Save.verifyCalled().access.asInstanceOf[Privileged].scopes shouldBe scopes1to4
    }

    "invoke repository save function with updated ropc application data access scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(ropcApplicationDataWithScopes(applicationId), Set.empty, scopes1to4)
      AuditServiceMock.Audit.thenReturnSuccess()
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes1to4))(hc))
      ApplicationRepoMock.Save.verifyCalled().access.asInstanceOf[Ropc].scopes shouldBe scopes1to4
    }

    "invoke audit service for privileged scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(privilegedApplicationDataWithScopes(applicationId), scopes1to3, Set.empty)
      AuditServiceMock.Audit.thenReturnSuccess()
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes2to4))(hc))
      AuditServiceMock.Audit.verifyCalledWith(ScopeRemoved, Map("removedScope" -> scope1), hc)
      AuditServiceMock.Audit.verifyCalledWith(ScopeAdded, Map("newScope" -> scope4), hc)
    }

    "invoke audit service for ropc scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(ropcApplicationDataWithScopes(applicationId), scopes1to3, Set.empty)
      AuditServiceMock.Audit.thenReturnSuccess()
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes2to4))(hc))
      AuditServiceMock.Audit.verifyCalledWith(ScopeRemoved, Map("removedScope" -> scope1), hc)
      AuditServiceMock.Audit.verifyCalledWith(ScopeAdded, Map("newScope" -> scope4), hc)
    }

  }

  "Access service read scopes function" should {

    "return privileged scopes when repository save succeeds" in new ScopeFixture {
      ApplicationRepoMock.Fetch.thenReturn(privilegedApplicationDataWithScopes(applicationId)(scopes1to4))
      await(accessService.readScopes(applicationId)) shouldBe ScopeResponse(scopes1to4)
    }

    "return ropc scopes when repository save succeeds" in new ScopeFixture {
      ApplicationRepoMock.Fetch.thenReturn(ropcApplicationDataWithScopes(applicationId)(scopes1to4))
      await(accessService.readScopes(applicationId)) shouldBe ScopeResponse(scopes1to4)
    }

  }

  "Access service read overrides function" should {

    "return the overrides saved on the application" in new OverridesFixture {
      ApplicationRepoMock.Fetch.thenReturn(standardApplicationDataWithOverrides(applicationId, overrides))
      await(accessService.readOverrides(applicationId)) shouldBe OverridesResponse(overrides)
    }

  }

  "Access service update overrides function" should {

    "invoke repository save function with updated application data access overrides" in new OverridesFixture {
      AuditServiceMock.Audit.thenReturnSuccess()
      val oldOverrides                 = Set[OverrideFlag](override1)
      val applicationDataWithOverrides = standardApplicationDataWithOverrides(applicationId, oldOverrides)
      ApplicationRepoMock.Fetch.thenReturn(applicationDataWithOverrides)
      ApplicationRepoMock.Save.thenReturn(applicationDataWithOverrides)

      val newOverrides = Set[OverrideFlag](override2, override3, override4)
      await(accessService.updateOverrides(applicationId, OverridesRequest(newOverrides))(hc))

      val capturedApplicationData = ApplicationRepoMock.Save.verifyCalled()
      capturedApplicationData.access.asInstanceOf[Standard].overrides shouldBe newOverrides
    }

    "overwrite the existing overrides with the new ones" in new OverridesFixture {
      AuditServiceMock.Audit.thenReturnSuccess()
      val grantWithoutConsent1 = GrantWithoutConsent(Set("scope1"))
      val grantWithoutConsent2 = GrantWithoutConsent(Set("scope2"))

      val oldOverrides                 = Set[OverrideFlag](grantWithoutConsent1)
      val applicationDataWithOverrides = standardApplicationDataWithOverrides(applicationId, oldOverrides)

      ApplicationRepoMock.Fetch.thenReturn(applicationDataWithOverrides)
      ApplicationRepoMock.Save.thenReturn(applicationDataWithOverrides)

      val newOverrides = Set[OverrideFlag](grantWithoutConsent2)
      await(accessService.updateOverrides(applicationId, OverridesRequest(newOverrides))(hc))

      val capturedApplicationData = ApplicationRepoMock.Save.verifyCalled()
      capturedApplicationData.access.asInstanceOf[Standard].overrides shouldBe Set(grantWithoutConsent2)
    }

    "invoke audit service" in new OverridesFixture {
      val oldOverrides = Set[OverrideFlag](override1)
      val newOverrides = Set[OverrideFlag](override2)

      val applicationDataWithOverrides = standardApplicationDataWithOverrides(applicationId, oldOverrides)
      ApplicationRepoMock.Fetch.thenReturn(applicationDataWithOverrides)
      ApplicationRepoMock.Save.thenReturn(applicationDataWithOverrides)

      await(accessService.updateOverrides(applicationId, OverridesRequest(newOverrides))(hc))

      AuditServiceMock.Audit.verifyCalledWith(OverrideRemoved, Map("removedOverride" -> override1.overrideType.toString), hc)
      AuditServiceMock.Audit.verifyCalledWith(OverrideAdded, Map("newOverride" -> override2.overrideType.toString), hc)
    }

  }

  trait Fixture extends ApplicationRepositoryMockModule with AuditServiceMockModule {

    val applicationId = ApplicationId.random

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val accessService = new AccessService(ApplicationRepoMock.aMock, AuditServiceMock.aMock)
  }

  trait ScopeFixture extends Fixture {
    val scope1     = "scope:key"
    val scope2     = "read:performance-test"
    val scope3     = "write:performance-test"
    val scope4     = "scope:key2"
    val scopes1to4 = Set(scope1, scope2, scope3, scope4)
    val scopes1to3 = Set(scope1, scope2, scope3)
    val scopes2to4 = Set(scope2, scope3, scope4)

    def mockApplicationRepositoryFetchAndSave(partialApplication: Set[String] => ApplicationData, fetchScopes: Set[String], saveScopes: Set[String] = Set.empty) = {
      ApplicationRepoMock.Fetch.thenReturn(partialApplication(fetchScopes))
      ApplicationRepoMock.Save.thenReturn(partialApplication(saveScopes))
    }
  }

  trait OverridesFixture extends Fixture {
    val override1 = GrantWithoutConsent(Set("scope1", "scope2"))
    val override2 = PersistLogin
    val override3 = SuppressIvForAgents(Set("scope1", "scope2"))
    val override4 = SuppressIvForOrganisations(Set("scope1", "scope2"))
    val overrides = Set[OverrideFlag](override1, override2, override3, override4)
  }

  private def privilegedApplicationDataWithScopes(applicationId: ApplicationId)(scopes: Set[String]): ApplicationData =
    ApplicationData(
      applicationId,
      "name",
      "normalisedName",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR, UserId.random)),
      None,
      "wso2ApplicationName",
      ApplicationTokens(
        Token(ClientId("a"), "c")
      ),
      ApplicationState(),
      Privileged(None, scopes),
      FixedClock.now,
      Some(FixedClock.now)
    )

  private def ropcApplicationDataWithScopes(applicationId: ApplicationId)(scopes: Set[String]): ApplicationData =
    ApplicationData(
      applicationId,
      "name",
      "normalisedName",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR, UserId.random)),
      None,
      "wso2ApplicationName",
      ApplicationTokens(
        Token(ClientId("a"), "c")
      ),
      ApplicationState(),
      Ropc(scopes),
      FixedClock.now,
      Some(FixedClock.now)
    )

  private def standardApplicationDataWithOverrides(applicationId: ApplicationId, overrides: Set[OverrideFlag]): ApplicationData =
    ApplicationData(
      applicationId,
      "name",
      "normalisedName",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR, UserId.random)),
      None,
      "wso2ApplicationName",
      ApplicationTokens(
        Token(ClientId("a"), "c")
      ),
      ApplicationState(),
      Standard(redirectUris = List.empty, overrides = overrides),
      FixedClock.now,
      Some(FixedClock.now)
    )
}
