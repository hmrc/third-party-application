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

import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, HOURS, MINUTES}

import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationStateFixtures
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.util._

class TermsOfUseInvitationOverdueJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateFixtures with FixedClock {

  trait Setup extends ApplicationRepositoryMockModule with TermsOfUseInvitationRepositoryMockModule with StoredApplicationFixtures with CommonApplicationId {

    val mockLockKeeper      = mock[TermsOfUseInvitationOverdueJobLockService]
    val mockTermsOfUseRepo  = TermsOfUseInvitationRepositoryMock.aMock
    val mockApplicationRepo = ApplicationRepoMock.aMock

    val nowInstant = instant

    val applicationId1 = ApplicationId.random
    val applicationId2 = ApplicationId.random
    val applicationId3 = ApplicationId.random

    val application1 = storedApp.copy(id = applicationId1)
    val recipients1  = application1.admins.map(_.emailAddress)
    val application2 = storedApp.copy(id = applicationId2)
    val recipients2  = application2.admins.map(_.emailAddress)
    val application3 = storedApp.copy(id = applicationId3)
    val recipients3  = application3.admins.map(_.emailAddress)

    val startDate1 = nowInstant.minus(100, ChronoUnit.DAYS)
    val dueBy1     = startDate1.plus(21, ChronoUnit.DAYS)
    val startDate2 = nowInstant.minus(59, ChronoUnit.DAYS)
    val dueBy2     = startDate2.plus(21, ChronoUnit.DAYS)
    val startDate3 = nowInstant.minus(32, ChronoUnit.DAYS)
    val dueBy3     = startDate3.plus(21, ChronoUnit.DAYS)

    val touInvite1 = TermsOfUseInvitation(applicationId1, startDate1, startDate1, dueBy1, None, EMAIL_SENT)
    val touInvite2 = TermsOfUseInvitation(applicationId2, startDate2, startDate2, dueBy2, None, EMAIL_SENT)
    val touInvite3 = TermsOfUseInvitation(applicationId3, startDate3, startDate3, dueBy3, None, REMINDER_EMAIL_SENT)

    val initialDelay = FiniteDuration(6, MINUTES)
    val interval     = FiniteDuration(8, HOURS)
    val jobConfig    = TermsOfUseInvitationOverdueJobConfig(initialDelay, interval, true)
    val job          = new TermsOfUseInvitationOverdueJob(mockLockKeeper, mockTermsOfUseRepo, mockApplicationRepo, clock, jobConfig)
  }

  "TermsOfUseInvitationOverdueJob" should {
    "update state of database record for single record" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.thenReturn(List(touInvite1))
      ApplicationRepoMock.Fetch.thenReturn(application1)
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.verifyCalledWith(nowInstant, EMAIL_SENT, REMINDER_EMAIL_SENT)
      TermsOfUseInvitationRepositoryMock.UpdateState.verifyCalledWith(applicationId1, OVERDUE)
    }

    "update state of database record for multiple records" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.thenReturn(List(touInvite1, touInvite2, touInvite3))
      ApplicationRepoMock.Fetch.thenReturn(application1)
      ApplicationRepoMock.Fetch.thenReturn(application2)
      ApplicationRepoMock.Fetch.thenReturn(application3)
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.verifyCalledWith(nowInstant, EMAIL_SENT, REMINDER_EMAIL_SENT)
      TermsOfUseInvitationRepositoryMock.UpdateState.verifyCalledWith(applicationId1, OVERDUE)
      TermsOfUseInvitationRepositoryMock.UpdateState.verifyCalledWith(applicationId2, OVERDUE)
      TermsOfUseInvitationRepositoryMock.UpdateState.verifyCalledWith(applicationId3, OVERDUE)
    }

    "not update state if no application record" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.thenReturn(List(touInvite1))
      ApplicationRepoMock.Fetch.thenReturnNone()

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.verifyCalledWith(nowInstant, EMAIL_SENT, REMINDER_EMAIL_SENT)
      TermsOfUseInvitationRepositoryMock.UpdateState.verifyNeverCalled()
    }

    "not update state if application record has state of DELETED" in new Setup with StoredApplicationFixtures {
      val deletedApp   = storedApp.withState(appStateDeleted)
      val touInviteDel = TermsOfUseInvitation(applicationId, startDate1, startDate1, dueBy1, None, EMAIL_SENT)

      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.thenReturn(List(touInviteDel))
      ApplicationRepoMock.Fetch.thenReturn(deletedApp)

      await(job.runJob)

      TermsOfUseInvitationRepositoryMock.FetchByStatusesBeforeDueBy.verifyCalledWith(nowInstant, EMAIL_SENT, REMINDER_EMAIL_SENT)
      TermsOfUseInvitationRepositoryMock.UpdateState.verifyNeverCalled()
    }
  }
}
