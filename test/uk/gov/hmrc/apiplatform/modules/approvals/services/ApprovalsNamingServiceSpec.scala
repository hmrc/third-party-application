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
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ValidatedApplicationName
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationNameValidationConfigMockModule, AuditServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ApprovalsNamingServiceSpec extends AsyncHmrcSpec {

  trait Setup
      extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with ApplicationNameValidationConfigMockModule
      with ApplicationTestData {

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val applicationId: ApplicationId = ApplicationId.random
    val accessType: AccessType       = AccessType.STANDARD
    val underTest                    = new ApprovalsNamingService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, ApplicationNameValidationConfigMock.aMock)
  }

  "ApplicationApprovalsNamingService" when {
    "validate application name" should {

      "allow valid name" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("HMRC"))

        val result = await(underTest.validateApplicationNameAndAudit(ValidatedApplicationName("my application name").get, applicationId, accessType))

        result shouldBe ValidName
      }

      "block a name with HMRC in" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("HMRC"))

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("Invalid name HMRC").get, applicationId))

        result shouldBe InvalidName
      }

      "block a name with multiple denyListed names in" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("InvalidName1", "InvalidName2", "InvalidName3"))

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("ValidName InvalidName1 InvalidName2").get, applicationId))

        result shouldBe InvalidName
      }

      "block an invalid ignoring case" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("InvalidName"))

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("invalidname").get, applicationId))

        result shouldBe InvalidName
      }

      "block a duplicate app name" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData)
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)

        private val duplicateName = "duplicate name"
        val result                = await(underTest.validateApplicationName(ValidatedApplicationName(duplicateName).get, applicationId))

        result shouldBe DuplicateName

        ApplicationRepoMock.FetchByName.verifyCalledWith(duplicateName)
      }

      "ignore a duplicate app name for local sandbox app" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData.copy(environment = "SANDBOX"))
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)

        private val duplicateName = "duplicate name"
        val result                = await(underTest.validateApplicationName(ValidatedApplicationName(duplicateName).get, applicationId))

        result shouldBe ValidName

        ApplicationRepoMock.FetchByName.verifyCalledWith(duplicateName)
      }

      "Ignore duplicate name check if not configured e.g. on a subordinate / sandbox environment" in new Setup {
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(false)

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("app name").get, applicationId))

        result shouldBe ValidName

        ApplicationRepoMock.FetchByName.veryNeverCalled()
      }

      "Ignore application when checking for duplicates if it is self application" in new Setup {
        ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList()

        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData)

        val result = await(underTest.validateApplicationName(ValidatedApplicationName("app name").get, applicationId))

        result shouldBe ValidName
      }
    }
  }
}
