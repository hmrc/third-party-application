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
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.PENDING_REQUESTER_VERIFICATION
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, NoMetricsGuiceOneAppPerSuite}

import java.time.{LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit.{DAYS, HOURS, SECONDS}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

class UpliftVerificationExpiryJobSpec
  extends AsyncHmrcSpec
    with MongoSupport
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with NoMetricsGuiceOneAppPerSuite {

  final val FixedTimeNow = LocalDateTime.now(ZoneOffset.UTC)
  final val expiryTimeInDays = 90
  final val sixty = 60
  final val twentyFour = 24

  trait Setup {
    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
    val mockStateHistoryRepository: StateHistoryRepository = mock[StateHistoryRepository]
    val mongoLockRepository: MongoLockRepository = app.injector.instanceOf[MongoLockRepository]
    val lockKeeperSuccess: () => Boolean = () => true

    val mockUpliftVerificationExpiryJobLockService: UpliftVerificationExpiryJobLockService =
      new UpliftVerificationExpiryJobLockService(mongoLockRepository) {
        override val ttl: Duration = 1.minutes
        override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))(ec) else Future.successful(None)
    }

    val upliftVerificationValidity: FiniteDuration = FiniteDuration(expiryTimeInDays, DAYS)
    val initialDelay: FiniteDuration = FiniteDuration(sixty, SECONDS)
    val interval: FiniteDuration = FiniteDuration(twentyFour, HOURS)
    val config: UpliftVerificationExpiryJobConfig = UpliftVerificationExpiryJobConfig(initialDelay, interval, enabled = true, upliftVerificationValidity)
    val underTest =
      new UpliftVerificationExpiryJob(mockUpliftVerificationExpiryJobLockService, mockApplicationRepository, mockStateHistoryRepository, clock, config)
  }

  "uplift verification expiry job execution" should {

    "expire all application uplifts having expiry date before the expiry time" in new Setup {
      val app1: ApplicationData = anApplicationData(ApplicationId.random, ClientId("aaa"))
      val app2: ApplicationData = anApplicationData(ApplicationId.random, ClientId("aaa"))

      when(mockApplicationRepository.fetchAllByStatusDetails(refEq(PENDING_REQUESTER_VERIFICATION), any[LocalDateTime]))
        .thenReturn(successful(List(app1, app2)))

      when(mockApplicationRepository.save(*[ApplicationData]))
        .thenAnswer((a: ApplicationData) => successful(a))

      await(underTest.execute)
      verify(mockApplicationRepository).fetchAllByStatusDetails(PENDING_REQUESTER_VERIFICATION, LocalDateTime.now(clock).minusDays(expiryTimeInDays))
      verify(mockApplicationRepository).save(app1.copy(state = testingState()))
      verify(mockApplicationRepository).save(app2.copy(state = testingState()))
      verify(mockStateHistoryRepository).insert(StateHistory(app1.id, State.TESTING,
        Actor("UpliftVerificationExpiryJob", ActorType.SCHEDULED_JOB), Some(PENDING_REQUESTER_VERIFICATION), changedAt = LocalDateTime.now(clock)))
      verify(mockStateHistoryRepository).insert(StateHistory(app2.id, State.TESTING,
        Actor("UpliftVerificationExpiryJob", ActorType.SCHEDULED_JOB), Some(PENDING_REQUESTER_VERIFICATION), changedAt = LocalDateTime.now(clock)))
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false

      await(underTest.execute)
    }

    "handle error on first database call to fetch all applications" in new Setup {
      when(mockApplicationRepository.fetchAllByStatusDetails(refEq(PENDING_REQUESTER_VERIFICATION), any[LocalDateTime])).thenReturn(
        Future.failed(new RuntimeException("A failure on executing fetchAllByStatusDetails db query"))
      )
      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe
        "The execution of scheduled job UpliftVerificationExpiryJob failed with error 'A failure on executing fetchAllByStatusDetails db query'." +
          " The next execution of the job will do retry."
    }

    "handle error on subsequent database call to update an application" in new Setup {
      val app1: ApplicationData = anApplicationData(ApplicationId.random, ClientId("aaa"))
      val app2: ApplicationData = anApplicationData(ApplicationId.random, ClientId("aaa"))

      when(mockApplicationRepository.fetchAllByStatusDetails(refEq(PENDING_REQUESTER_VERIFICATION), any[LocalDateTime]))
        .thenReturn(Future.successful(List(app1, app2)))
      when(mockApplicationRepository.save(any[ApplicationData])).thenReturn(
        Future.failed(new RuntimeException("A failure on executing save db query"))
      )

      val result: underTest.Result = await(underTest.execute)

      verify(mockApplicationRepository).fetchAllByStatusDetails(PENDING_REQUESTER_VERIFICATION, LocalDateTime.now(clock).minusDays(expiryTimeInDays))
      result.message shouldBe
        "The execution of scheduled job UpliftVerificationExpiryJob failed with error 'A failure on executing save db query'." +
          " The next execution of the job will do retry."
    }
  }

  def anApplicationData(id: ApplicationId, prodClientId: ClientId, state: ApplicationState = testingState()): ApplicationData = {
    ApplicationData(
      id,
      s"myApp-${id.value}",
      s"myapp-${id.value}",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR, UserId.random)),
      Some("description"),
      "myapplication",
      ApplicationTokens(
        Token(prodClientId, "ccc")
      ),
      state,
      Standard(),
      LocalDateTime.now(clock),
      Some(LocalDateTime.now(clock)))
  }
}
