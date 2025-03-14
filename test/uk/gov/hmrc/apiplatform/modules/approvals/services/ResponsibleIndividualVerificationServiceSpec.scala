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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{SubmissionId, _}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualVerificationId,
  ResponsibleIndividualVerificationWithDetails
}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ResponsibleIndividualVerificationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.util._

class ResponsibleIndividualVerificationServiceSpec extends AsyncHmrcSpec with StoredApplicationFixtures {

  trait Setup
      extends SubmissionsTestData
      with StateHistoryRepositoryMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule {

    val appName                 = ApplicationName("my shiny app")
    val submissionInstanceIndex = 0
    val responsibleIndividual   = ResponsibleIndividual.build("bob example", "bob@example.com")
    val requestingAdminName     = "Bob Fleming"
    val requestingAdminEmail    = "bob.fleming@yahoo.com"

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val application: StoredApplication = storedApp.copy(
      state = appStatePendingRIVerification.copy(requestedByName = Some("Rick Deckard"), requestedByEmailAddress = Some("rick@submitter.com")),
      access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData)),
      name = appName
    )

    val underTest = new ResponsibleIndividualVerificationService(
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      StateHistoryRepoMock.aMock,
      clock
    )

    val riVerificationId = ResponsibleIndividualVerificationId.random

    val riVerification            = ResponsibleIndividualToUVerification(
      riVerificationId,
      application.id,
      SubmissionId.random,
      0,
      appName,
      instant
    )
    val riVerificationWithDetails = ResponsibleIndividualVerificationWithDetails(riVerification, responsibleIndividual, "Rick Deckard", "rick@submitter.com")
  }

  "createNewToUVerification" should {
    "create a new ToU verification object and save it to the database" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Save.thenReturnSuccess()

      val result = await(underTest.createNewToUVerification(application, submissionId, submissionInstanceIndex))

      result.applicationId shouldBe applicationId
      result.submissionId shouldBe submissionId
      result.submissionInstance shouldBe submissionInstanceIndex
      result.applicationName shouldBe appName

      ResponsibleIndividualVerificationRepositoryMock.Save.verifyCalledWith(result)
    }
  }

  "createNewTouUpliftVerification" should {
    "create a new ToU uplift verification object and save it to the database" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Save.thenReturnSuccess()

      val result = await(underTest.createNewTouUpliftVerification(application, submissionId, submissionInstanceIndex, requestingAdminName, LaxEmailAddress(requestingAdminEmail)))

      result.applicationId shouldBe applicationId
      result.submissionId shouldBe submissionId
      result.submissionInstance shouldBe submissionInstanceIndex
      result.applicationName shouldBe appName

      ResponsibleIndividualVerificationRepositoryMock.Save.verifyCalledWith(result)
    }
  }
  "getVerification" should {
    "get a RI verification record" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification)

      val result = await(underTest.getVerification(riVerificationId.value))

      result.isDefined shouldBe true
      result.get.id shouldBe riVerificationId
      result.get.applicationName shouldBe appName

      ResponsibleIndividualVerificationRepositoryMock.Fetch.verifyCalledWith(riVerificationId)
    }
  }
}
