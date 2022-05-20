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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.REMINDERS_SENT
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import java.time.temporal.ChronoUnit.SECONDS
import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, HOURS, MINUTES}

class ResponsibleIndividualVerificationRemovalJobSpec extends AsyncHmrcSpec with MongoSpecSupport with BeforeAndAfterAll with ApplicationStateUtil {
  trait Setup extends ApplicationServiceMockModule with EmailConnectorMockModule with ResponsibleIndividualVerificationRepositoryMockModule {
    val mockLockKeeper = mock[ResponsibleIndividualVerificationRemovalJobLockKeeper]
    val mockRepo = ResponsibleIndividualVerificationRepositoryMock.aMock
    val timeNow = LocalDateTime.now
    val fixedClock = Clock.fixed(timeNow.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    val riName = "bob responsible"
    val riEmail = "bob.responsible@example.com"
    val appName = "my app"
    val requesterName = "bob requester"
    val requesterEmail = "bob.requester@example.com"
    val importantSubmissionData = ImportantSubmissionData(None, ResponsibleIndividual.build(riName, riEmail), Set.empty, TermsAndConditionsLocation.InDesktopSoftware, PrivacyPolicyLocation.InDesktopSoftware, List.empty)
    val app = anApplicationData(
      ApplicationId.random,
      access = Standard(importantSubmissionData = Some(importantSubmissionData)),
      state = ApplicationState().toPendingResponsibleIndividualVerification(requesterEmail, requesterName, fixedClock)
    ).copy(name = appName)
    val initialDelay = FiniteDuration(1, MINUTES)
    val interval = FiniteDuration(1, HOURS)
    val removalInterval = FiniteDuration(20, DAYS)
    val jobConfig = ResponsibleIndividualVerificationRemovalJobConfig(initialDelay, interval, removalInterval, true)
    val job = new ResponsibleIndividualVerificationRemovalJob(mockLockKeeper, mockRepo, EmailConnectorMock.aMock, ApplicationServiceMock.aMock, fixedClock, jobConfig)
  }

  "ResponsibleIndividualVerificationRemovalJob" should {
    "send email and remove database record" in new Setup {
      ApplicationServiceMock.Fetch.thenReturn(app)
      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.thenReturnSuccess()

      val verification = ResponsibleIndividualVerification(ResponsibleIndividualVerificationId.random, ApplicationId.random, Submission.Id.random, 0, appName, LocalDateTime.now)
      ResponsibleIndividualVerificationRepositoryMock.FetchByStateAndAge.thenReturn(verification)
      ResponsibleIndividualVerificationRepositoryMock.Delete.thenReturnSuccess()

      await(job.runJob)

      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.verifyCalledWith(riName, requesterEmail, appName, requesterName)
      ResponsibleIndividualVerificationRepositoryMock.FetchByStateAndAge.verifyCalledWith(REMINDERS_SENT, timeNow.minus(removalInterval.toSeconds, SECONDS))
      ResponsibleIndividualVerificationRepositoryMock.Delete.verifyCalledWith(verification.id)
    }
  }
}
