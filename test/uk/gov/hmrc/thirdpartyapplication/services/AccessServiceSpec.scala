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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, OverrideFlag}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.thirdpartyapplication.controllers.{OverridesResponse, ScopeResponse}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.util._

class AccessServiceSpec extends AsyncHmrcSpec with CollaboratorTestData with FixedClock with CommonApplicationId {

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

  trait Fixture extends ApplicationRepositoryMockModule with AuditServiceMockModule {

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

    def mockApplicationRepositoryFetchAndSave(partialApplication: Set[String] => StoredApplication, fetchScopes: Set[String], saveScopes: Set[String] = Set.empty) = {
      ApplicationRepoMock.Fetch.thenReturn(partialApplication(fetchScopes))
      ApplicationRepoMock.Save.thenReturn(partialApplication(saveScopes))
    }
  }

  trait OverridesFixture extends Fixture {
    val override1 = OverrideFlag.GrantWithoutConsent(Set("scope1", "scope2"))
    val override2 = OverrideFlag.PersistLogin
    val override3 = OverrideFlag.SuppressIvForAgents(Set("scope1", "scope2"))
    val override4 = OverrideFlag.SuppressIvForOrganisations(Set("scope1", "scope2"))
    val overrides = Set[OverrideFlag](override1, override2, override3, override4)
  }

  private def privilegedApplicationDataWithScopes(applicationId: ApplicationId)(scopes: Set[String]): StoredApplication =
    StoredApplication(
      applicationId,
      ApplicationName("name"),
      "normalisedName",
      Set("user@example.com".admin()),
      None,
      "wso2ApplicationName",
      ApplicationTokens(
        StoredToken(ClientId("a"), "c")
      ),
      ApplicationStateExamples.testing,
      Access.Privileged(None, scopes),
      instant,
      Some(instant)
    )

  private def ropcApplicationDataWithScopes(applicationId: ApplicationId)(scopes: Set[String]): StoredApplication =
    StoredApplication(
      applicationId,
      ApplicationName("name"),
      "normalisedName",
      Set("user@example.com".admin()),
      None,
      "wso2ApplicationName",
      ApplicationTokens(
        StoredToken(ClientId("a"), "c")
      ),
      ApplicationStateExamples.testing,
      Access.Ropc(scopes),
      instant,
      Some(instant)
    )

  private def standardApplicationDataWithOverrides(applicationId: ApplicationId, overrides: Set[OverrideFlag]): StoredApplication =
    StoredApplication(
      applicationId,
      ApplicationName("name"),
      "normalisedName",
      Set("user@example.com".admin()),
      None,
      "wso2ApplicationName",
      ApplicationTokens(
        StoredToken(ClientId("a"), "c")
      ),
      ApplicationStateExamples.testing,
      Access.Standard(redirectUris = List.empty, overrides = overrides),
      instant,
      Some(instant)
    )
}
