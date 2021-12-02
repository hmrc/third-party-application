/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.approvals.services

import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.modules.approvals.services.ApprovalsApplicationNamingService
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationNameValidationConfigMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models._

class ApplicationApprovalsNamingServiceSpec extends AsyncHmrcSpec {

  trait Setup 
    extends AuditServiceMockModule 
    with ApplicationRepositoryMockModule
    with ApplicationNameValidationConfigMockModule
    with ApplicationTestData {

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val applicationId: ApplicationId = ApplicationId.random
    val underTest = new ApprovalsApplicationNamingService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, ApplicationNameValidationConfigMock.aMock)
  }

  "ApplicationApprovalsNamingService" when {
    "validate application name" should {

      "allow valid name" in new Setup { 
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameBlackList.thenReturns(List("HMRC"))

        val result = await(underTest.validateApplicationName("my application name", applicationId))

        result shouldBe Valid
      }

      "block a name with HMRC in" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameBlackList.thenReturns(List("HMRC"))    

        val result = await(underTest.validateApplicationName("Invalid name HMRC", applicationId))

        result shouldBe Invalid.invalidName
      }

      "block a name with multiple blacklisted names in" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameBlackList.thenReturns(List("InvalidName1", "InvalidName2", "InvalidName3"))

        val result = await(underTest.validateApplicationName("ValidName InvalidName1 InvalidName2", applicationId))

        result shouldBe Invalid.invalidName
      }

      "block an invalid ignoring case" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturnEmptyList()
        ApplicationNameValidationConfigMock.NameBlackList.thenReturns(List("InvalidName"))

        val result = await(underTest.validateApplicationName("invalidname", applicationId))

        result shouldBe Invalid.invalidName
      }

      "block a duplicate app name" in new Setup {
        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random))
        ApplicationNameValidationConfigMock.NameBlackList.thenReturnsAnEmptyList
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)

        private val duplicateName = "duplicate name"
        val result = await(underTest.validateApplicationName(duplicateName, applicationId))

        result shouldBe Invalid.duplicateName

        ApplicationRepoMock.FetchByName.verifyCalledWith(duplicateName)
      }

      "Ignore duplicate name check if not configured e.g. on a subordinate / sandbox environment" in new Setup {
        ApplicationNameValidationConfigMock.NameBlackList.thenReturnsAnEmptyList
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(false)

        val result = await(underTest.validateApplicationName("app name", applicationId))

        result shouldBe Valid

        ApplicationRepoMock.FetchByName.veryNeverCalled()
      }

      "Ignore application when checking for duplicates if it is self application" in new Setup {
        ApplicationNameValidationConfigMock.NameBlackList.thenReturnsAnEmptyList

        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = applicationId))

        val result = await(underTest.validateApplicationName("app name", applicationId))

        result shouldBe Valid
      }
    }

    "isDuplicateNonTestingName" should {
      val appName = "app name"

      "detect duplicate if another non-testing app has the same name" in new Setup {      
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)
        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random, state = productionState("test@example.com")))
        val isDuplicate = await(underTest.isDuplicateName(appName, applicationId))

        isDuplicate shouldBe true
      }
      "not detect duplicate if another non-testing app has the same name but duplicate checking is turned off" in new Setup {      
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(false)
        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random, state = productionState("test@example.com")))
        val isDuplicate = await(underTest.isDuplicateName(appName, applicationId))

        isDuplicate shouldBe false
      }
      "not detect duplicate if a test app has the same name" in new Setup { 
        ApplicationNameValidationConfigMock.ValidateForDuplicateAppNames.thenReturns(true)
        ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random, state = testingState))
        val isDuplicate = await(underTest.isDuplicateName(appName, applicationId))

        isDuplicate shouldBe false
      }
    }
  }
}