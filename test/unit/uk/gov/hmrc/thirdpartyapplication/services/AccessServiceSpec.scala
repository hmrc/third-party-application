/*
 * Copyright 2019 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito.{verify, when}
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.thirdpartyapplication.controllers.{OverridesRequest, OverridesResponse, ScopeRequest, ScopeResponse}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{OverrideAdded, OverrideRemoved, ScopeAdded, ScopeRemoved}
import uk.gov.hmrc.thirdpartyapplication.services.{AccessService, AuditAction, AuditService}

import scala.concurrent.Future
import scala.concurrent.Future.successful

class AccessServiceSpec extends UnitSpec with MockitoSugar {

  "Access service update scopes function" should {

    "invoke repository save function with updated privileged application data access scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(privilegedApplicationDataWithScopes(applicationId), Set.empty, scopes1to4)
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes1to4))(hc))
      captureApplicationRepositorySaveArgument().access.asInstanceOf[Privileged].scopes shouldBe scopes1to4
    }

    "invoke repository save function with updated ropc application data access scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(ropcApplicationDataWithScopes(applicationId), Set.empty, scopes1to4)
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes1to4))(hc))
      captureApplicationRepositorySaveArgument().access.asInstanceOf[Ropc].scopes shouldBe scopes1to4
    }

    "invoke audit service for privileged scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(privilegedApplicationDataWithScopes(applicationId), scopes1to3, Set.empty)
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes2to4))(hc))
      verify(mockAuditService).audit(ScopeRemoved, Map("removedScope" -> scope1))(hc)
      verify(mockAuditService).audit(ScopeAdded, Map("newScope" -> scope4))(hc)
    }

    "invoke audit service for ropc scopes" in new ScopeFixture {
      mockApplicationRepositoryFetchAndSave(ropcApplicationDataWithScopes(applicationId), scopes1to3, Set.empty)
      await(accessService.updateScopes(applicationId, ScopeRequest(scopes2to4))(hc))
      verify(mockAuditService).audit(ScopeRemoved, Map("removedScope" -> scope1))(hc)
      verify(mockAuditService).audit(ScopeAdded, Map("newScope" -> scope4))(hc)
    }

  }

  "Access service read scopes function" should {

    "return privileged scopes when repository save succeeds" in new ScopeFixture {
      mockApplicationRepositoryFetchToReturn(successful(Some(privilegedApplicationDataWithScopes(applicationId)(scopes1to4))))
      await(accessService.readScopes(applicationId)) shouldBe ScopeResponse(scopes1to4)
    }

    "return ropc scopes when repository save succeeds" in new ScopeFixture {
      mockApplicationRepositoryFetchToReturn(successful(Some(ropcApplicationDataWithScopes(applicationId)(scopes1to4))))
      await(accessService.readScopes(applicationId)) shouldBe ScopeResponse(scopes1to4)
    }

  }

  "Access service read overrides function" should {

    "return the overrides saved on the application" in new OverridesFixture {
      mockApplicationRepositoryFetchToReturn(successful(Some(standardApplicationDataWithOverrides(applicationId, overrides))))
      await(accessService.readOverrides(applicationId)) shouldBe OverridesResponse(overrides)
    }

  }

  "Access service update overrides function" should {

    "invoke repository save function with updated application data access overrides" in new OverridesFixture {
      val oldOverrides = Set[OverrideFlag](override1)
      val applicationDataWithOverrides = standardApplicationDataWithOverrides(applicationId, oldOverrides)
      mockApplicationRepositoryFetchToReturn(successful(Some(applicationDataWithOverrides)))
      mockApplicationRepositorySaveToReturn(successful(applicationDataWithOverrides))

      val newOverrides = Set[OverrideFlag](override2, override3, override4)
      await(accessService.updateOverrides(applicationId, OverridesRequest(newOverrides))(hc))

      val capturedApplicationData = captureApplicationRepositorySaveArgument()
      capturedApplicationData.access.asInstanceOf[Standard].overrides shouldBe newOverrides
    }

    "overwrite the existing overrides with the new ones" in new OverridesFixture {
      val grantWithoutConsent1 = GrantWithoutConsent(Set("scope1"))
      val grantWithoutConsent2 = GrantWithoutConsent(Set("scope2"))

      val oldOverrides = Set[OverrideFlag](grantWithoutConsent1)
      val applicationDataWithOverrides = standardApplicationDataWithOverrides(applicationId, oldOverrides)

      mockApplicationRepositoryFetchToReturn(successful(Some(applicationDataWithOverrides)))
      mockApplicationRepositorySaveToReturn(successful(applicationDataWithOverrides))

      val newOverrides = Set[OverrideFlag](grantWithoutConsent2)
      await(accessService.updateOverrides(applicationId, OverridesRequest(newOverrides))(hc))

      val capturedApplicationData = captureApplicationRepositorySaveArgument()
      capturedApplicationData.access.asInstanceOf[Standard].overrides shouldBe Set(grantWithoutConsent2)
    }

    "invoke audit service" in new OverridesFixture {
      val oldOverrides = Set[OverrideFlag](override1)
      val newOverrides = Set[OverrideFlag](override2)

      val applicationDataWithOverrides = standardApplicationDataWithOverrides(applicationId, oldOverrides)
      mockApplicationRepositoryFetchToReturn(successful(Some(applicationDataWithOverrides)))
      mockApplicationRepositorySaveToReturn(successful(applicationDataWithOverrides))

      await(accessService.updateOverrides(applicationId, OverridesRequest(newOverrides))(hc))

      verify(mockAuditService).audit(OverrideRemoved, Map("removedOverride" -> override1.overrideType.toString))(hc)
      verify(mockAuditService).audit(OverrideAdded, Map("newOverride" -> override2.overrideType.toString))(hc)
    }

  }

  trait Fixture {

    val applicationId = UUID.randomUUID()
    implicit val hc = HeaderCarrier()

    val mockApplicationRepository = mock[ApplicationRepository]
    val mockAuditService = mock[AuditService]

    val accessService = new AccessService(mockApplicationRepository, mockAuditService)

    val applicationDataArgumentCaptor = ArgumentCaptor.forClass(classOf[ApplicationData])

    def mockApplicationRepositoryFetchToReturn(eventualMaybeApplicationData: Future[Option[ApplicationData]]) =
      when(mockApplicationRepository.fetch(any[UUID])).thenReturn(eventualMaybeApplicationData)

    def mockApplicationRepositorySaveToReturn(eventualApplicationData: Future[ApplicationData]) =
      when(mockApplicationRepository.save(any[ApplicationData])).thenReturn(eventualApplicationData)

    def captureApplicationRepositorySaveArgument(): ApplicationData = {
      verify(mockApplicationRepository).save(applicationDataArgumentCaptor.capture())
      applicationDataArgumentCaptor.getValue
    }

    def captureApplicationRepositorySaveArgumentsAccessScopes(): Set[String] = {
      verify(mockApplicationRepository).save(applicationDataArgumentCaptor.capture())
      applicationDataArgumentCaptor.getValue.access.asInstanceOf[Privileged].scopes
    }

    when(mockAuditService.audit(any[AuditAction], any[Map[String, String]])(any[HeaderCarrier])).thenReturn(successful(AuditResult.Success))

  }

  trait ScopeFixture extends Fixture {
    val scope1 = "scope:key"
    val scope2 = "read:performance-test"
    val scope3 = "write:performance-test"
    val scope4 = "scope:key2"
    val scopes1to4 = Set(scope1, scope2, scope3, scope4)
    val scopes1to3 = Set(scope1, scope2, scope3)
    val scopes2to4 = Set(scope2, scope3, scope4)

    def mockApplicationRepositoryFetchAndSave(partialApplication: (Set[String]) => ApplicationData,
                                              fetchScopes: Set[String], saveScopes: Set[String] = Set.empty) = {
      mockApplicationRepositoryFetchToReturn(successful(Some(partialApplication(fetchScopes))))
      mockApplicationRepositorySaveToReturn(successful(partialApplication(saveScopes)))
    }
  }

  trait OverridesFixture extends Fixture {
    val override1 = GrantWithoutConsent(Set("scope1", "scope2"))
    val override2 = PersistLogin()
    val override3 = SuppressIvForAgents(Set("scope1", "scope2"))
    val override4 = SuppressIvForOrganisations(Set("scope1", "scope2"))
    val overrides = Set[OverrideFlag](override1, override2, override3, override4)
  }

  private def privilegedApplicationDataWithScopes(applicationId: UUID)(scopes: Set[String]): ApplicationData =
    ApplicationData(
      applicationId, "name", "normalisedName",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR)), None,
      "wso2Username", "wso2Password", "wso2ApplicationName",
      ApplicationTokens(
        EnvironmentToken("a", "b", "c"),
        EnvironmentToken("1", "2", "3")),
      ApplicationState(), Privileged(None, scopes))

  private def ropcApplicationDataWithScopes(applicationId: UUID)(scopes: Set[String]): ApplicationData =
    ApplicationData(
      applicationId, "name", "normalisedName",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR)), None,
      "wso2Username", "wso2Password", "wso2ApplicationName",
      ApplicationTokens(
        EnvironmentToken("a", "b", "c"),
        EnvironmentToken("1", "2", "3")),
      ApplicationState(), Ropc(scopes))

  private def standardApplicationDataWithOverrides(applicationId: UUID, overrides: Set[OverrideFlag]): ApplicationData =
    ApplicationData(
      applicationId, "name", "normalisedName",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR)), None,
      "wso2Username", "wso2Password", "wso2ApplicationName",
      ApplicationTokens(
        EnvironmentToken("a", "b", "c"),
        EnvironmentToken("1", "2", "3")),
      ApplicationState(), Standard(redirectUris = Seq.empty, overrides = overrides))

}
