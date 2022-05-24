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

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId, ResponsibleIndividualVerificationWithDetails}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.DeclineApprovalsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule

class ResponsibleIndividualVerificationServiceSpec extends AsyncHmrcSpec {
  trait Setup
    extends ApplicationTestData
    with SubmissionsTestData
    with ApplicationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with ApplicationServiceMockModule
    with DeclineApprovalsServiceMockModule
    with SubmissionsServiceMockModule
    with EmailConnectorMockModule
    with FixedClock {

    val appName = "my shiny app"
    val submissionInstanceIndex = 0

    val responsibleIndividualVerificationRepo = mock[ResponsibleIndividualVerificationRepository]
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")
    val testImportantSubmissionData = ImportantSubmissionData(Some("organisationUrl.com"),
                              responsibleIndividual,
                              Set(ServerLocation.InUK),
                              TermsAndConditionsLocation.InDesktopSoftware,
                              PrivacyPolicyLocation.InDesktopSoftware,
                              List.empty)
    val application: ApplicationData = anApplicationData(
                              applicationId,
                              pendingResponsibleIndividualVerificationState("bob@fastshow.com"),
                              access = Standard(importantSubmissionData = Some(testImportantSubmissionData))).copy(name = appName)

    val underTest = new ResponsibleIndividualVerificationService(
                              responsibleIndividualVerificationRepo,
                              ApplicationRepoMock.aMock, 
                              StateHistoryRepoMock.aMock, 
                              ApplicationServiceMock.aMock, 
                              SubmissionsServiceMock.aMock,
                              EmailConnectorMock.aMock,
                              DeclineApprovalsServiceMock.aMock,
                              clock)

    val riVerificationId = ResponsibleIndividualVerificationId.random
    val riVerification = ResponsibleIndividualVerification(
          riVerificationId,
          application.id,
          Submission.Id.random,
          0,
          appName,
          LocalDateTime.now(clock))
    val riVerificationWithDetails = ResponsibleIndividualVerificationWithDetails(riVerification, responsibleIndividual)
  }

  "createNewVerification" should {
    "create a new verification object and save it to the database" in new Setup {
      when(responsibleIndividualVerificationRepo.save(*)).thenAnswer((details: ResponsibleIndividualVerification) => Future.successful(details))

      val result = await(underTest.createNewVerification(application, submissionId, submissionInstanceIndex))

      result.applicationId shouldBe applicationId
      result.submissionId shouldBe submissionId
      result.submissionInstance shouldBe submissionInstanceIndex
      result.applicationName shouldBe appName

      verify(responsibleIndividualVerificationRepo).save(result)
    }
  }

  "getVerification" should {
    "get a RI verification record" in new Setup {
      when(responsibleIndividualVerificationRepo.fetch(*[ResponsibleIndividualVerificationId])).thenAnswer(Future.successful(Some(riVerification)))

      val result = await(underTest.getVerification(riVerificationId.value))

      result.isDefined shouldBe true
      result.get.id shouldBe riVerificationId
      result.get.applicationName shouldBe appName

      verify(responsibleIndividualVerificationRepo).fetch(riVerificationId)
    }
  }

  "accept" should {
    "return verification record with details and add ToU acceptance if application is found" in new Setup {
      when(responsibleIndividualVerificationRepo.fetch(*[ResponsibleIndividualVerificationId])).thenAnswer(Future.successful(Some(riVerification)))
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenReturn(application)
      ApplicationServiceMock.AddTermsOfUseAcceptance.thenReturn(application)
      StateHistoryRepoMock.Insert.thenAnswer()
      when(responsibleIndividualVerificationRepo.delete(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(HasSucceeded))

      val result = await(underTest.accept(riVerificationId.value))

      result shouldBe 'Right
      result shouldBe Right(riVerificationWithDetails)
      result.right.value.verification.id shouldBe riVerificationId
      result.right.value.verification.applicationName shouldBe appName

      val acceptance = ApplicationServiceMock.AddTermsOfUseAcceptance.verifyCalledWith(riVerification.applicationId)
      acceptance.responsibleIndividual shouldBe responsibleIndividual
      acceptance.submissionId shouldBe riVerification.submissionId

      val savedStateHistory = StateHistoryRepoMock.Insert.verifyCalled()
      savedStateHistory.previousState shouldBe Some(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION)
      savedStateHistory.state shouldBe State.PENDING_GATEKEEPER_APPROVAL

      val savedAppData = ApplicationRepoMock.Save.verifyCalled()
      savedAppData.state.name shouldBe State.PENDING_GATEKEEPER_APPROVAL

      verify(responsibleIndividualVerificationRepo).fetch(riVerificationId)
    }

    "return correct error message if application is not found" in new Setup {
      when(responsibleIndividualVerificationRepo.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(Some(riVerification)))
      ApplicationRepoMock.Fetch.thenReturnNone()

      val result = await(underTest.accept(riVerificationId.value))

      result shouldBe Left(s"Application with id ${riVerification.applicationId} not found")
    }

    "return correct error message if verification record is not found" in new Setup {
      when(responsibleIndividualVerificationRepo.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(None))

      val result = await(underTest.accept(riVerificationId.value))

      result shouldBe Left(s"responsibleIndividualVerification not found")
    }
  }

  "decline" should {
    "return verification record if verification record is found" in new Setup {
      when(responsibleIndividualVerificationRepo.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(Some(riVerification)))
      ApplicationRepoMock.Fetch.thenReturn(application)
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      DeclineApprovalsServiceMock.Decline.thenReturn(DeclineApprovalsService.Actioned(application))
      EmailConnectorMock.SendResponsibleIndividualDeclined.thenReturnSuccess()
      when(responsibleIndividualVerificationRepo.delete(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(HasSucceeded))

      val result = await(underTest.decline(riVerificationId.value))

      result shouldBe Right(riVerification)
    }

    "return correct error message if verification record is not found" in new Setup {
      when(responsibleIndividualVerificationRepo.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(None))

      val result = await(underTest.decline(riVerificationId.value))

      result shouldBe Left(s"responsibleIndividualVerification not found")
    }
  }
}
