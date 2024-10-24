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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, HOURS, MINUTES}

import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationStateExamples
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationCommandDispatcherMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ProductionCredentialsRequestExpiredJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup extends ApplicationRepositoryMockModule with ApplicationCommandDispatcherMockModule with ApplicationTestData {

    val mockLockKeeper = mock[ProductionCredentialsRequestExpiredJobLockService]
    val timeNow        = now

    val riName         = "bob responsible"
    val riEmail        = "bob.responsible@example.com"
    val appName        = ApplicationName("my app")
    val requesterName  = "bob requester"
    val requesterEmail = "bob.requester@example.com"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app            = anApplicationData(
      ApplicationId.random
    ).copy(
      access = Access.Standard(importantSubmissionData = Some(importantSubmissionData)),
      state = ApplicationStateExamples.pendingResponsibleIndividualVerification(requesterEmail, requesterName),
      name = appName
    )
    val initialDelay   = FiniteDuration(1, MINUTES)
    val interval       = FiniteDuration(1, HOURS)
    val deleteInterval = FiniteDuration(10, DAYS)
    val jobConfig      = ProductionCredentialsRequestExpiredJobConfig(initialDelay, interval, true, deleteInterval)
    val job            = new ProductionCredentialsRequestExpiredJob(mockLockKeeper, ApplicationRepoMock.aMock, ApplicationCommandDispatcherMock.aMock, clock, jobConfig)
    val recipients     = app.collaborators.map(_.emailAddress)
  }

  "ProductionCredentialsRequestExpiredJob" should {
    "delete applications, send emails correctly and delete any notification records" in new Setup {
      ApplicationRepoMock.FetchByStatusDetailsAndEnvironmentForDeleteJob.thenReturn(app)
      ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccess(app)

      await(job.runJob)

      ApplicationCommandDispatcherMock.Dispatch.verifyCalledWith(app.id)
    }
  }
}
