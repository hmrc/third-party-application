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

import java.time.{Duration => JavaTimeDuration}
import java.util.concurrent.TimeUnit.{DAYS, HOURS, SECONDS}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.test.MongoSupport

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQueries
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository
import uk.gov.hmrc.thirdpartyapplication.util._

class UpliftVerificationExpiryJobSpec
    extends AsyncHmrcSpec
    with MongoSupport
    with BeforeAndAfterAll
    with ApplicationStateFixtures
    with NoMetricsGuiceOneAppPerSuite
    with CollaboratorTestData
    with StoredApplicationFixtures
    with FixedClock {

  final val FixedTimeNow     = instant
  final val expiryTimeInDays = 90
  final val sixty            = 60
  final val twentyFour       = 24

  trait Setup extends SubmissionsTestData with ApplicationRepositoryMockModule {
    val mockStateHistoryRepository: StateHistoryRepository = mock[StateHistoryRepository]
    val mockSubmissionsService: SubmissionsService         = mock[SubmissionsService]
    val mongoLockRepository: MongoLockRepository           = app.injector.instanceOf[MongoLockRepository]
    val lockKeeperSuccess: () => Boolean                   = () => true

    val mockUpliftVerificationExpiryJobLockService: UpliftVerificationExpiryJobLockService =
      new UpliftVerificationExpiryJobLockService(mongoLockRepository) {
        override val ttl: Duration = 1.minutes

        override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
          if (lockKeeperSuccess()) body.map(value => Some(value))(ec) else Future.successful(None)
      }

    val upliftVerificationValidity: FiniteDuration = FiniteDuration(expiryTimeInDays, DAYS)
    val initialDelay: FiniteDuration               = FiniteDuration(sixty, SECONDS)
    val interval: FiniteDuration                   = FiniteDuration(twentyFour, HOURS)
    val config: UpliftVerificationExpiryJobConfig  = UpliftVerificationExpiryJobConfig(initialDelay, interval, enabled = true, upliftVerificationValidity)

    val underTest =
      new UpliftVerificationExpiryJob(mockUpliftVerificationExpiryJobLockService, ApplicationRepoMock.aMock, mockStateHistoryRepository, mockSubmissionsService, clock, config)
  }

  "uplift verification expiry job execution" should {

    "expire all application uplifts having expiry date before the expiry time" in new Setup {
      val app1: StoredApplication =
        storedApp.withId(ApplicationId.random).withState(appStatePendingRequesterVerification.copy(requestedByEmailAddress = Some("requester1@example.com")))
      val app2: StoredApplication =
        storedApp.withId(ApplicationId.random).withState(appStatePendingRequesterVerification.copy(requestedByEmailAddress = Some("requester2@example.com")))
      val expectedDate            = instant.minus(JavaTimeDuration.ofDays(expiryTimeInDays))
      val expectedQry             = ApplicationQueries.applicationsByStateAndDate(State.PENDING_REQUESTER_VERIFICATION, expectedDate)

      ApplicationRepoMock.FetchApplicationsByQuery.thenReturnsFor(expectedQry, app1, app2)

      ApplicationRepoMock.Save.thenAnswer((a: StoredApplication) => successful(a))
      when(mockStateHistoryRepository.insert(*))
        .thenAnswer((h: StateHistory) => successful(h))
      when(mockSubmissionsService.declineSubmission(*[ApplicationId], *, *))
        .thenReturn(successful(Some(aSubmission)))

      await(underTest.execute)
      ApplicationRepoMock.Save.verifyCalledWith(app1.withState(appStateTesting))
      ApplicationRepoMock.Save.verifyCalledWith(app2.withState(appStateTesting))
      verify(mockStateHistoryRepository).insert(StateHistory(
        app1.id,
        State.TESTING,
        Actors.ScheduledJob("UpliftVerificationExpiryJob"),
        Some(State.PENDING_REQUESTER_VERIFICATION),
        changedAt = instant
      ))
      verify(mockStateHistoryRepository).insert(StateHistory(
        app2.id,
        State.TESTING,
        Actors.ScheduledJob("UpliftVerificationExpiryJob"),
        Some(State.PENDING_REQUESTER_VERIFICATION),
        changedAt = instant
      ))
      verify(mockSubmissionsService).declineSubmission(
        app1.id,
        "requester1@example.com",
        "Automatically declined because requester did not verify"
      )
      verify(mockSubmissionsService).declineSubmission(
        app2.id,
        "requester2@example.com",
        "Automatically declined because requester did not verify"
      )
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false

      await(underTest.execute)
    }

    "handle error on first database call to fetch all applications" in new Setup {
      ApplicationRepoMock.FetchApplicationsByQuery.thenFails(new RuntimeException("A failure on executing fetchAllByStatusDetails db query"))

      val result = await(underTest.execute)

      result.message shouldBe
        "The execution of scheduled job UpliftVerificationExpiryJob failed with error 'A failure on executing fetchAllByStatusDetails db query'." +
        " The next execution of the job will do retry."
    }

    "handle error on subsequent database call to update an application" in new Setup {
      val app1: StoredApplication = storedApp.withId(ApplicationId.random).withState(appStateTesting)
      val app2: StoredApplication = storedApp.withId(ApplicationId.random).withState(appStateTesting)

      val expectedDate = instant.minus(JavaTimeDuration.ofDays(expiryTimeInDays))
      val expectedQry  = ApplicationQueries.applicationsByStateAndDate(State.PENDING_REQUESTER_VERIFICATION, expectedDate)
      ApplicationRepoMock.FetchApplicationsByQuery.thenReturnsFor(expectedQry, app1, app2)

      ApplicationRepoMock.Save.thenFail(new RuntimeException("A failure on executing save db query"))

      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe
        "The execution of scheduled job UpliftVerificationExpiryJob failed with error 'A failure on executing save db query'." +
        " The next execution of the job will do retry."
    }
  }
}
