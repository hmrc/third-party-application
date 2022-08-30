/*
 * Copyright 2022 HM Revenue & Customs
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

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualToUVerification, ResponsibleIndividualVerificationId, ResponsibleIndividualVerificationWithDetails}
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.DeclineApprovalsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class ResponsibleIndividualVerificationServiceSpec extends AsyncHmrcSpec {

  trait Setup
      extends ApplicationTestData
      with SubmissionsTestData
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with ApplicationServiceMockModule
      with DeclineApprovalsServiceMockModule
      with SubmissionsServiceMockModule
      with EmailConnectorMockModule
      with FixedClock {

    val appName                 = "my shiny app"
    val submissionInstanceIndex = 0
    val responsibleIndividual   = ResponsibleIndividual.build("bob example", "bob@example.com")

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val application: ApplicationData = anApplicationData(
      applicationId,
      pendingResponsibleIndividualVerificationState("Rick Deckard", "rick@submitter.com"),
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    ).copy(name = appName)

    val underTest = new ResponsibleIndividualVerificationService(
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      ApplicationServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      EmailConnectorMock.aMock,
      DeclineApprovalsServiceMock.aMock,
      clock
    )

    val riVerificationId = ResponsibleIndividualVerificationId.random

    val riVerification            = ResponsibleIndividualToUVerification(
      riVerificationId,
      application.id,
      Submission.Id.random,
      0,
      appName,
      LocalDateTime.now(clock)
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

  "createNewUpdateVerification" should {
    "create a new update verification object and save it to the database" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Save.thenReturnSuccess()

      val result = await(underTest.createNewUpdateVerification(application, submissionId, submissionInstanceIndex, responsibleIndividual.fullName.value, responsibleIndividual.emailAddress.value))

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

  "decline" should {
    "return verification record if verification record is found" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification)
      ApplicationRepoMock.Fetch.thenReturn(application)
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      DeclineApprovalsServiceMock.Decline.thenReturn(DeclineApprovalsService.Actioned(application))
      EmailConnectorMock.SendResponsibleIndividualDeclined.thenReturnSuccess()

      val result = await(underTest.decline(riVerificationId.value))

      result shouldBe Right(riVerification)
    }

    "return correct error message if verification record is not found" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturnNothing

      val result = await(underTest.decline(riVerificationId.value))

      result shouldBe Left(s"responsibleIndividualVerification not found")
    }
  }
}
