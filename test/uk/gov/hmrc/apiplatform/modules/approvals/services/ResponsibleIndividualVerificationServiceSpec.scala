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
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import scala.concurrent.Future

class ResponsibleIndividualVerificationServiceSpec extends AsyncHmrcSpec {
  trait Setup extends ApplicationTestData{
    val applicationId = ApplicationId.random
    val appName = "my shiny app"
    val appData = anApplicationData(applicationId, testingState()).copy(name = appName)
    val submissionId = Submission.Id.random
    val submissionInstanceIndex = 0
    val dao = mock[ResponsibleIndividualVerificationDAO]
    val underTest = new ResponsibleIndividualVerificationService(dao)
    val riVerificationId = ResponsibleIndividualVerificationId.random
    val riVerification = ResponsibleIndividualVerification(
          riVerificationId,
          ApplicationId.random,
          Submission.Id.random,
          0,
          appName)

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
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
}
