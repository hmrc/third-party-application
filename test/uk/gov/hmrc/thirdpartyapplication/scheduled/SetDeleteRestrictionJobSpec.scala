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
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationStateFixtures, DeleteRestriction}
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiPlatformEventsConnector, DisplayEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util._

class SetDeleteRestrictionJobSpec
    extends AsyncHmrcSpec
    with MongoSupport
    with CleanMongoCollectionSupport
    with ApplicationStateFixtures
    with NoMetricsGuiceOneAppPerSuite
    with StoredApplicationFixtures
    with CollaboratorTestData
    with FixedClock {

  implicit lazy val materializer: Materializer = NoMaterializer
  implicit val metrics: Metrics                = app.injector.instanceOf[Metrics]

  val applicationRepository = new ApplicationRepository(mongoComponent, metrics)
  val mockEventsConnector   = mock[ApiPlatformEventsConnector]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(applicationRepository.ensureIndexes())
  }

  trait Setup {
    val lockKeeperSuccess: () => Boolean         = () => true
    val mongoLockRepository: MongoLockRepository = app.injector.instanceOf[MongoLockRepository]

    val mockSetDeleteRestrictionJobLockService: SetDeleteRestrictionJobLockService =
      new SetDeleteRestrictionJobLockService(mongoLockRepository) {
        override val ttl: Duration = 1.minutes

        override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
          if (lockKeeperSuccess()) body.map(value => Some(value))(ec) else Future.successful(None)
      }

    val jobConfig: SetDeleteRestrictionJobConfig = SetDeleteRestrictionJobConfig(true)
    val underTest                                = new SetDeleteRestrictionJob(mockSetDeleteRestrictionJobLockService, applicationRepository, mockEventsConnector, clock, jobConfig)
  }

  "SetDeleteRestrictionJob" should {
    "update deleteRestriction fields in database" in new Setup {

      val appId1 = ApplicationId.random
      val appId2 = ApplicationId.random
      val appId3 = ApplicationId.random

      val bulkInsert = List(
        testApplicationData(appId1, true),
        testApplicationData(appId2, false),
        testApplicationData(appId3, false)
      )

      await(Future.sequence(bulkInsert.map(i => applicationRepository.save(i))))

      val reason = "reason"
      val actor  = Actors.GatekeeperUser("bob")
      val event  = DisplayEvent(appId2, instant, actor, "eventTagDescription", "Application auto delete blocked", List(reason))
      when(mockEventsConnector.query(eqTo(appId2), eqTo(Some("APP_LIFECYCLE")), eqTo(None))(*)).thenReturn(Future.successful(List(event)))
      when(mockEventsConnector.query(eqTo(appId3), eqTo(Some("APP_LIFECYCLE")), eqTo(None))(*)).thenReturn(Future.successful(List.empty))

      await(underTest.runJob)

      val actualApp1: StoredApplication = await(applicationRepository.fetch(appId1)).get
      actualApp1.deleteRestriction shouldBe DeleteRestriction.NoRestriction
      val actualApp2: StoredApplication = await(applicationRepository.fetch(appId2)).get
      actualApp2.deleteRestriction shouldBe DeleteRestriction.DoNotDelete(reason, actor, instant)
      val actualApp3: StoredApplication = await(applicationRepository.fetch(appId3)).get
      actualApp3.deleteRestriction shouldBe DeleteRestriction.DoNotDelete("Set by process - Reason not found", Actors.ScheduledJob("SetDeleteRestrictionJob"), instant)
    }
  }

  def testApplicationData(
      id: ApplicationId,
      autoDelete: Boolean
    ): StoredApplication =
    storedApp
      .withId(id)
      .copy(
        tokens = ApplicationTokens(StoredToken(ClientId.random, "ccc")),
        allowAutoDelete = autoDelete
      )
}
