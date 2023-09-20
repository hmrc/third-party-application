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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.EMAIL_SENT
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class TermsOfUseInvitationReminderJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil with FixedClock {

  trait Setup extends EmailConnectorMockModule with ApplicationRepositoryMockModule with SubmissionsServiceMockModule
      with TermsOfUseInvitationRepositoryMockModule with ApplicationTestData with SubmissionsTestData {

    val mockLockKeeper        = mock[TermsOfUseInvitationReminderJobLockService]
    val mockTermsOfUseRepo    = TermsOfUseInvitationRepositoryMock.aMock
    val mockApplicationRepo   = ApplicationRepoMock.aMock
    val mockSubmissionService = SubmissionsServiceMock.aMock

    val nowInstant = Instant.now(clock).truncatedTo(MILLIS)
    val dueBy      = nowInstant.plus(30, ChronoUnit.DAYS)

    val applicationId1 = ApplicationId.random
    val applicationId2 = ApplicationId.random
    val applicationId3 = ApplicationId.random

    val application1 = anApplicationData(applicationId1)
    val recipients1  = application1.admins.map(_.emailAddress)
    val application2 = anApplicationData(applicationId2)
    val recipients2  = application2.admins.map(_.emailAddress)
    val application3 = anApplicationData(applicationId3)
    val recipients3  = application3.admins.map(_.emailAddress)

    val startDate1 = nowInstant.minus(100, ChronoUnit.DAYS)
    val dueBy1     = startDate1.plus(21, ChronoUnit.DAYS)
    val startDate2 = nowInstant.minus(59, ChronoUnit.DAYS)
    val dueBy2     = startDate2.plus(21, ChronoUnit.DAYS)
    val startDate3 = nowInstant.minus(32, ChronoUnit.DAYS)
    val dueBy3     = startDate3.plus(21, ChronoUnit.DAYS)

    val touInvite1 = TermsOfUseInvitation(applicationId1, startDate1, startDate1, dueBy1, None, EMAIL_SENT)
    val touInvite2 = TermsOfUseInvitation(applicationId2, startDate2, startDate2, dueBy2, None, EMAIL_SENT)
    val touInvite3 = TermsOfUseInvitation(applicationId3, startDate3, startDate3, dueBy3, None, EMAIL_SENT)

    val submission1 = aSubmission.copy(applicationId = applicationId1)
    val submission2 = aSubmission.copy(applicationId = applicationId2)

    val initialDelay     = FiniteDuration(6, MINUTES)
    val interval         = FiniteDuration(8, HOURS)
    val reminderInterval = FiniteDuration(30, DAYS)
    val jobConfig        = TermsOfUseInvitationReminderJobConfig(initialDelay, interval, true, reminderInterval)
    val job              = new TermsOfUseInvitationReminderJob(mockLockKeeper, mockTermsOfUseRepo, mockApplicationRepo, mockSubmissionService, EmailConnectorMock.aMock, clock, jobConfig)
  }

  "TermsOfUseInvitationReminderJob" should {
    "send email correctly and update state of database record for single record with submission" in new Setup {
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

    "send email correctly and update state of database record for single record with no submission" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.thenReturn(List(touInvite1))
      ApplicationRepoMock.Fetch.thenReturn(application1)
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      EmailConnectorMock.SendNewTermsOfUseInvitation.thenReturnSuccess()
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.thenReturn()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.verifyCalledWith(EMAIL_SENT, dueBy)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyCalledWith(touInvite1.dueBy, application1.name, recipients1)
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyCalledWith(applicationId1)
    }

    "send email correctly and update state of database record for multiple records" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.thenReturn(List(touInvite1, touInvite2, touInvite3))
      ApplicationRepoMock.Fetch.thenReturn(application1)
      ApplicationRepoMock.Fetch.thenReturn(application2)
      ApplicationRepoMock.Fetch.thenReturn(application3)
      SubmissionsServiceMock.FetchLatest.thenReturnWhen(submission1)
      SubmissionsServiceMock.FetchLatest.thenReturnWhen(submission2)
      SubmissionsServiceMock.FetchLatest.thenReturnNoneWhen(applicationId3)
      EmailConnectorMock.SendNewTermsOfUseInvitation.thenReturnSuccess()
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.thenReturn()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.verifyCalledWith(EMAIL_SENT, dueBy)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyCalledWith(touInvite1.dueBy, application1.name, recipients1)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyCalledWith(touInvite2.dueBy, application2.name, recipients2)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyCalledWith(touInvite3.dueBy, application3.name, recipients3)
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyCalledWith(applicationId1)
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyCalledWith(applicationId2)
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyCalledWith(applicationId3)
    }

    "not send email or update state of database record for single record with submission with 2 instances" in new Setup {
      val submissionWith2Instances = declinedSubmission.copy(applicationId = applicationId1)
      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.thenReturn(List(touInvite1))
      SubmissionsServiceMock.FetchLatest.thenReturn(submissionWith2Instances)

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.verifyCalledWith(EMAIL_SENT, dueBy)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyNeverCalled()
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyNeverCalled()
    }

    "not send email if no application record" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.thenReturn(List(touInvite1))
      ApplicationRepoMock.Fetch.thenReturnNone()
      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.verifyCalledWith(EMAIL_SENT, dueBy)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyNeverCalled()
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyNeverCalled()
    }

    "not send email if application record has state of DELETED" in new Setup with ApplicationTestData {
      val deletedAppId1 = ApplicationId.random
      val deletedApp    = anApplicationData(applicationId = deletedAppId1, state = deletedState("requestedBy@example.com"))
      val touInviteDel  = TermsOfUseInvitation(deletedAppId1, startDate1, startDate1, dueBy1, None, EMAIL_SENT)

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.thenReturn(List(touInviteDel))
      ApplicationRepoMock.Fetch.thenReturn(deletedApp)
      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusBeforeDueBy.verifyCalledWith(EMAIL_SENT, dueBy)
      EmailConnectorMock.SendNewTermsOfUseInvitation.verifyNeverCalled()
      TermsOfUseInvitationRepositoryMock.UpdateReminderSent.verifyNeverCalled()
    }
  }
}
