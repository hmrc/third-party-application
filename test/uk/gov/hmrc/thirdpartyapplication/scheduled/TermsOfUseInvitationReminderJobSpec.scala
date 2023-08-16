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

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, HOURS, MINUTES}

import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.EMAIL_SENT
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class TermsOfUseInvitationReminderJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil with FixedClock {

  trait Setup extends EmailConnectorMockModule with ApplicationRepositoryMockModule with SubmissionsServiceMockModule
      with TermsOfUseInvitationRepositoryMockModule with ApplicationTestData with SubmissionsTestData {

    val mockLockKeeper        = mock[TermsOfUseInvitationReminderJobLockService]
    val mockTermsOfUseRepo    = TermsOfUseInvitationRepositoryMock.aMock
    val mockApplicationRepo   = ApplicationRepoMock.aMock
    val mockSubmissionService = SubmissionsServiceMock.aMock

    val nowInstant     = Instant.now(clock).truncatedTo(MILLIS)
    val dueBy          = nowInstant.plus(30, ChronoUnit.DAYS)

    val applicationId1 = ApplicationId.random
    val applicationId2 = ApplicationId.random
    val applicationId3 = ApplicationId.random

    val application1   = anApplicationData(applicationId1)
    val recipients1    = application1.admins.map(_.emailAddress)
    val application2   = anApplicationData(applicationId2)
    val application3   = anApplicationData(applicationId3)

    val startDate1     = nowInstant.minus(100, ChronoUnit.DAYS)
    val dueBy1         = startDate1.plus(60, ChronoUnit.DAYS)
    val startDate2     = nowInstant.minus(32, ChronoUnit.DAYS)
    val dueBy2         = startDate2.plus(60, ChronoUnit.DAYS)
    val startDate3     = nowInstant.minus(1, ChronoUnit.DAYS)
    val dueBy3         = startDate3.plus(60, ChronoUnit.DAYS)

    val touInvite1     = TermsOfUseInvitation(applicationId1, startDate1, startDate1, dueBy1, None, EMAIL_SENT)
    val touInvite2     = TermsOfUseInvitation(applicationId2, startDate2, startDate2, dueBy2, None, EMAIL_SENT)
    val touInvite3     = TermsOfUseInvitation(applicationId3, startDate3, startDate3, dueBy3, None, EMAIL_SENT)

    val submission1 = aSubmission.copy(applicationId = applicationId1)

    val initialDelay     = FiniteDuration(1, MINUTES)
    val interval         = FiniteDuration(1, HOURS)
    val reminderInterval = FiniteDuration(30, DAYS)
    val jobConfig        = TermsOfUseInvitationReminderJobConfig(initialDelay, interval, true, reminderInterval)
    val job              = new TermsOfUseInvitationReminderJob(mockLockKeeper, mockTermsOfUseRepo, mockApplicationRepo, mockSubmissionService, EmailConnectorMock.aMock, clock, jobConfig)
  }

  "TermsOfUseInvitationReminderJob" should {
    "send email correctly and update state of database record" in new Setup {

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.thenReturn(List(touInvite1))
      ApplicationRepoMock.Fetch.thenReturn(application1)
      SubmissionsServiceMock.FetchLatest.thenReturn(submission1)
      EmailConnectorMock.SendNewTermsOfUseInvitation.thenReturnSuccess()
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.thenReturn()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.verifyCalledWith(EMAIL_SENT, dueBy)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyCalledWith(touInvite1.dueBy, application1.name, recipients1)
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyCalledWith(applicationId1)
    }
  }
}
