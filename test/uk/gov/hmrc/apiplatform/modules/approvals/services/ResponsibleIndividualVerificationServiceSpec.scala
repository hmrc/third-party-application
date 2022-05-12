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
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationDAO
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, ImportantSubmissionData, PrivacyPolicyLocation, ResponsibleIndividual, Standard, TermsAndConditionsLocation}
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ResponsibleIndividualVerificationServiceSpec extends AsyncHmrcSpec {
  trait Setup extends ApplicationTestData with ApplicationServiceMockModule {
    val applicationId = ApplicationId.random
    val appName = "my shiny app"
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")
    val importantSubmissionData = ImportantSubmissionData(
      None, responsibleIndividual, Set.empty, TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware, List.empty)
    val stdAccess = Standard(importantSubmissionData = Some(importantSubmissionData))
    val appData = anApplicationData(applicationId, testingState()).copy(name = appName, access = stdAccess)
    val submissionId = Submission.Id.random
    val submissionInstanceIndex = 0
    val dao = mock[ResponsibleIndividualVerificationDAO]
    val underTest = new ResponsibleIndividualVerificationService(dao, ApplicationServiceMock.aMock)
    val riVerificationId = ResponsibleIndividualVerificationId.random
    val riVerification = ResponsibleIndividualVerification(
          riVerificationId,
          ApplicationId.random,
          Submission.Id.random,
          0,
          appName)
    val riVerificationWithDetails = ResponsibleIndividualVerificationWithDetails(riVerification, responsibleIndividual)
  }

  "createNewVerification" should {
    "create a new verification object and save it to the database" in new Setup {
      when(dao.save(*)).thenAnswer((details: ResponsibleIndividualVerification) => Future.successful(details))

      val result = await(underTest.createNewVerification(appData, submissionId, submissionInstanceIndex))

      result.applicationId shouldBe applicationId
      result.submissionId shouldBe submissionId
      result.submissionInstance shouldBe submissionInstanceIndex
      result.applicationName shouldBe appName

      verify(dao).save(result)
    }
  }

  "getVerification" should {
    "get a RI verification record" in new Setup {
      when(dao.fetch(*[ResponsibleIndividualVerificationId])).thenAnswer(Future.successful(Some(riVerification)))

      val result = await(underTest.getVerification(riVerificationId.value))

      result.isDefined shouldBe true
      result.get.id shouldBe riVerificationId
      result.get.applicationName shouldBe appName

      verify(dao).fetch(result.get.id)
    }
  }

  "accept" should {
    "return verification record with details and add ToU acceptance if application is found" in new Setup {
      ApplicationServiceMock.Fetch.thenReturn(appData)
      ApplicationServiceMock.AddTermsOfUseAcceptance.thenReturn(appData)
      when(dao.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(Some(riVerification)))

      val result = await(underTest.accept(riVerificationId.value))

      result shouldBe Right(riVerificationWithDetails)
      val acceptance = ApplicationServiceMock.AddTermsOfUseAcceptance.verifyCalledWith(riVerification.applicationId)
      acceptance.responsibleIndividual shouldBe responsibleIndividual
      acceptance.submissionId shouldBe riVerification.submissionId
    }

    "return correct error message if application is not found" in new Setup {
      when(dao.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(Some(riVerification)))
      ApplicationServiceMock.Fetch.thenReturnNothing()

      val result = await(underTest.accept(riVerificationId.value))

      result shouldBe Left(s"Application with id ${riVerification.applicationId} not found")
    }

    "return correct error message if verification record is not found" in new Setup {
      when(dao.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(None))

      val result = await(underTest.accept(riVerificationId.value))

      result shouldBe Left(s"responsibleIndividualVerification not found")
    }
  }

  "decline" should {
    "return verification record if verification record is found" in new Setup {
      when(dao.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(Some(riVerification)))

      val result = await(underTest.decline(riVerificationId.value))

      result shouldBe Right(riVerification)
    }
    "return correct error message if verification record is not found" in new Setup {
      when(dao.fetch(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(None))

      val result = await(underTest.decline(riVerificationId.value))

      result shouldBe Left(s"responsibleIndividualVerification not found")
    }
  }
}
