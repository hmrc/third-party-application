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

package uk.gov.hmrc.apiplatform.modules.uplift.services

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationNameValidationConfigMockModule, AuditServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

class UpliftNamingServiceSpec extends AsyncHmrcSpec {

  trait Setup
      extends AuditServiceMockModule
      with ApplicationStateUtil
      with ApplicationRepositoryMockModule
      with ApplicationNameValidationConfigMockModule
      with ApplicationTestData {

    val applicationId: ApplicationId = ApplicationId.random

    val underTest = new UpliftNamingService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, ApplicationNameValidationConfigMock.aMock)
  }

  "validate application name" should {

    "allow valid name" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()
      ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("HMRC"))

      val result = await(underTest.validateApplicationName("my application name", None))

      result shouldBe ValidName
    }

    "block a name with HMRC in" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()
      ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("HMRC"))

      val result = await(underTest.validateApplicationName("Invalid name HMRC", None))

      result shouldBe InvalidName
    }

    "block a name with multiple denyListed names in" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()
      ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("InvalidName1", "InvalidName2", "InvalidName3"))

      val result = await(underTest.validateApplicationName("ValidName InvalidName1 InvalidName2", None))

      result shouldBe InvalidName
    }

    "block an invalid ignoring case" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()
      ApplicationNameValidationConfigMock.NameDenyList.thenReturns(List("InvalidName"))

      val result = await(underTest.validateApplicationName("invalidname", None))

      result shouldBe InvalidName
    }

    "block a duplicate app name" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random))
      ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList
      ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)

      private val duplicateName = "duplicate name"
      val result                = await(underTest.validateApplicationName(duplicateName, None))

      result shouldBe DuplicateName

      ApplicationRepoMock.FetchByName.verifyCalledWith(duplicateName)
    }

    "Ignore duplicate name check if not configured e.g. on a subordinate / sandbox environment" in new Setup {
      ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList
      ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(false)

      val result = await(underTest.validateApplicationName("app name", None))

      result shouldBe ValidName

      ApplicationRepoMock.FetchByName.veryNeverCalled()
    }

    "Ignore application when checking for duplicates if it is self application" in new Setup {
      ApplicationNameValidationConfigMock.NameDenyList.thenReturnsAnEmptyList

      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = applicationId))

      val result = await(underTest.validateApplicationName("app name", Some(applicationId)))

      result shouldBe ValidName
    }
  }

  "isDuplicateName" should {
    val appName = "app name"

    "detect duplicate if another app has the same name" in new Setup {
      ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)
      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random))
      val isDuplicate = await(underTest.isDuplicateName(appName, None))

      isDuplicate shouldBe true
    }

    "not detect duplicate if another app has the same name but is in Sandbox (local)" in new Setup {
      ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)
      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random, environment = Environment.SANDBOX))
      val isDuplicate = await(underTest.isDuplicateName(appName, None))

      isDuplicate shouldBe false
    }

    "not detect duplicate if another app has the same name but also has the same applicationId" in new Setup {
      ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)
      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = applicationId))
      val isDuplicate = await(underTest.isDuplicateName(appName, Some(applicationId)))

      isDuplicate shouldBe false
    }

    "not detect duplicate if another app has the same name but duplicate checking is turned off" in new Setup {
      ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(false)
      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = applicationId))
      val isDuplicate = await(underTest.isDuplicateName(appName, None))

      isDuplicate shouldBe false
    }
  }

}
