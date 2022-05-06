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
}
