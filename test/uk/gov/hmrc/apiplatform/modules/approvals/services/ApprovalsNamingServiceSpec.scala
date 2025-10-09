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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, ValidatedApplicationName}
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.ApplicationNameValidationResult
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQueries
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationNameValidationConfigMockModule, AuditServiceMockModule, QueryServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class ApprovalsNamingServiceSpec extends AsyncHmrcSpec {

  trait Setup
      extends AuditServiceMockModule
      with QueryServiceMockModule
      with ApplicationNameValidationConfigMockModule
      with ApplicationWithCollaboratorsFixtures {

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val applicationId: ApplicationId = ApplicationId.random
    val accessType: AccessType       = AccessType.STANDARD
    val underTest                    = new ApprovalsNamingService(AuditServiceMock.aMock, QueryServiceMock.aMock, ApplicationNameValidationConfigMock.aMock)
  }

  "ApplicationApprovalsNamingService" when {
    "validate application name" should {

      "allow valid name" in new Setup {
        QueryServiceMock.FetchApplicationsByQuery.thenReturnsNoApps()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("HMRC"))

        val result = await(underTest.validateApplicationNameAndAudit(ValidatedApplicationName("my application name").get, applicationId, accessType))

        result shouldBe ApplicationNameValidationResult.Valid
      }

      "block a name with HMRC in" in new Setup {
        QueryServiceMock.FetchApplicationsByQuery.thenReturnsNoApps()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("HMRC"))

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("Invalid name HMRC").get, applicationId))

        result shouldBe ApplicationNameValidationResult.Invalid
      }

      "block a name with multiple denyListed names in" in new Setup {
        QueryServiceMock.FetchApplicationsByQuery.thenReturnsNoApps()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("InvalidName1", "InvalidName2", "InvalidName3"))

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("ValidName InvalidName1 InvalidName2").get, applicationId))

        result shouldBe ApplicationNameValidationResult.Invalid
      }

      "block an invalid ignoring case" in new Setup {
        QueryServiceMock.FetchApplicationsByQuery.thenReturnsNoApps()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("InvalidName"))

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("invalidname").get, applicationId))

        result shouldBe ApplicationNameValidationResult.Invalid
      }

      "block a duplicate app name" in new Setup {
        private val duplicateName = "duplicate name"
        QueryServiceMock.FetchApplicationsByQuery.thenReturnsFor(ApplicationQueries.applicationsByName(duplicateName), standardApp)
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)

        val result = await(underTest.validateApplicationName(ValidatedApplicationName(duplicateName).get, applicationId))

        result shouldBe ApplicationNameValidationResult.Duplicate
      }

      "ignore a duplicate app name for local sandbox app" in new Setup {
        private val duplicateName = "duplicate name"
        QueryServiceMock.FetchApplicationsByQuery.thenReturnsNoAppsFor(ApplicationQueries.applicationsByName(duplicateName)) // Filter on query eliminates sandbox
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)

        val result = await(underTest.validateApplicationName(ValidatedApplicationName(duplicateName).get, applicationId))

        result shouldBe ApplicationNameValidationResult.Valid
      }

      "Ignore duplicate name check if not configured e.g. on a subordinate / sandbox environment" in new Setup {
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(false)

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("app name").get, applicationId))

        result shouldBe ApplicationNameValidationResult.Valid
      }

      "Ignore application when checking for duplicates if it is self application" in new Setup {
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()

        QueryServiceMock.FetchApplicationsByQuery.thenReturns(standardApp)

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("app name").get, applicationId))

        result shouldBe ApplicationNameValidationResult.Valid
      }
    }
  }
}
