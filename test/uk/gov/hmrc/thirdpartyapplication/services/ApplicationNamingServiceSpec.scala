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

package uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models._

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

class ApplicationNamingServiceSpec extends AsyncHmrcSpec {
  
  trait Setup 
    extends AuditServiceMockModule
    with ApplicationRepositoryMockModule
    with ApplicationTestData {

    val applicationId: ApplicationId = ApplicationId.random
    
    val mockNameValidationConfig = mock[ApplicationNameValidationConfig]
    val underTest = new ApplicationNamingService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, mockNameValidationConfig)
  }
  
   val mockNameValidationConfig = mock[ApplicationNameValidationConfig]

  "validate application name" should {

    "allow valid name" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("HMRC"))

      val result = await(underTest.validateApplicationName("my application name", None))

      result shouldBe Valid
    }

    "block a name with HMRC in" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("HMRC"))

      val result = await(underTest.validateApplicationName("Invalid name HMRC", None))

      result shouldBe Invalid.invalidName
    }

    "block a name with multiple blacklisted names in" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("InvalidName1", "InvalidName2", "InvalidName3"))

      val result = await(underTest.validateApplicationName("ValidName InvalidName1 InvalidName2", None))

      result shouldBe Invalid.invalidName
    }

    "block an invalid ignoring case" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List("InvalidName"))

      val result = await(underTest.validateApplicationName("invalidname", None))

      result shouldBe Invalid.invalidName
    }

    "block a duplicate app name" in new Setup {
      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = ApplicationId.random))

      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List.empty[String])

      private val duplicateName = "duplicate name"
      val result = await(underTest.validateApplicationName(duplicateName, None))

      result shouldBe Invalid.duplicateName

      ApplicationRepoMock.FetchByName.verifyCalledWith(duplicateName)
    }

    "Ignore duplicate name check if not configured e.g. on a subordinate / sandbox environment" in new Setup {
      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List.empty[String])

      when(mockNameValidationConfig.validateForDuplicateAppNames)
        .thenReturn(false)

      val result = await(underTest.validateApplicationName("app name", None))

      result shouldBe Valid

      ApplicationRepoMock.FetchByName.veryNeverCalled()
    }

    "Ignore application when checking for duplicates if it is self application" in new Setup {
      when(mockNameValidationConfig.nameBlackList)
        .thenReturn(List.empty)

      ApplicationRepoMock.FetchByName.thenReturn(anApplicationData(applicationId = applicationId))

      val result = await(underTest.validateApplicationName("app name", Some(applicationId)))

      result shouldBe Valid
    }
  }
}
