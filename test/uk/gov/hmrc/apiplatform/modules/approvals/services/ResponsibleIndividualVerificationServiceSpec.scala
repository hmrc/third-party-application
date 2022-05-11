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

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerification
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationDAO
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ResponsibleIndividualVerificationServiceSpec extends AsyncHmrcSpec {
  trait Setup 
    extends ApplicationTestData 
    with ApplicationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with FixedClock {

    val applicationId = ApplicationId.random
    val appName = "my shiny app"
    val appData = anApplicationData(applicationId, testingState()).copy(name = appName)
    val submissionId = Submission.Id.random
    val submissionInstanceIndex = 0
    val responsibleIndividualVerificationDao = mock[ResponsibleIndividualVerificationDAO]
    val testImportantSubmissionData = ImportantSubmissionData(Some("organisationUrl.com"),
                              ResponsibleIndividual(ResponsibleIndividual.Name("Jed Thomas"), ResponsibleIndividual.EmailAddress("jed@fastshow.com")),
                              Set(ServerLocation.InUK),
                              TermsAndConditionsLocation.InDesktopSoftware,
                              PrivacyPolicyLocation.InDesktopSoftware,
                              List.empty)
    val application: ApplicationData = anApplicationData(
                              applicationId, 
                              pendingResponsibleIndividualVerificationState("bob@fastshow.com"),
                              access = Standard(importantSubmissionData = Some(testImportantSubmissionData)))

    val underTest = new ResponsibleIndividualVerificationService(responsibleIndividualVerificationDao, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, clock)
  
    val riVerificationId = ResponsibleIndividualVerificationId.random
    val riVerification = ResponsibleIndividualVerification(
          riVerificationId,
          application.id,
          Submission.Id.random,
          0,
          appName)
  }

  "createNewVerification" should {
    "create a new verification object and save it to the database" in new Setup {
      when(responsibleIndividualVerificationDao.save(*)).thenAnswer((details: ResponsibleIndividualVerification) => Future.successful(details))

      val result = await(underTest.createNewVerification(appData, submissionId, submissionInstanceIndex))

      result.applicationId shouldBe applicationId
      result.submissionId shouldBe submissionId
      result.submissionInstance shouldBe submissionInstanceIndex
      result.applicationName shouldBe appName

      verify(responsibleIndividualVerificationDao).save(result)
    }
  }

  "getVerification" should {
    "get a RI verification record" in new Setup {
      when(responsibleIndividualVerificationDao.fetch(*[ResponsibleIndividualVerificationId])).thenAnswer(Future.successful(Some(riVerification)))

      val result = await(underTest.getVerification(riVerificationId.value))

      result.isDefined shouldBe true
      result.get.id shouldBe riVerificationId
      result.get.applicationName shouldBe appName

      verify(responsibleIndividualVerificationDao).fetch(riVerificationId)
    }
  }

  "accept" should {
    "successfully accept the ToU" in new Setup {
      when(responsibleIndividualVerificationDao.fetch(*[ResponsibleIndividualVerificationId])).thenAnswer(Future.successful(Some(riVerification)))
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenReturn(application)
      StateHistoryRepoMock.Insert.thenAnswer()
      when(responsibleIndividualVerificationDao.delete(*[ResponsibleIndividualVerificationId])).thenReturn(Future.successful(Unit))

      val result = await(underTest.accept(riVerificationId.value))

      result shouldBe 'Right
      result.right.value.id shouldBe riVerificationId
      result.right.value.applicationName shouldBe appName

      verify(responsibleIndividualVerificationDao).fetch(riVerificationId)
    }
  }
}
