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
import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.stream.Materializer

import play.api.libs.json.Format
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationName, CoreApplicationData}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, CollaboratorTestData, NoMetricsGuiceOneAppPerSuite}

class ResetLastAccessDateJobSpec
    extends AsyncHmrcSpec
    with MongoSupport
    with CleanMongoCollectionSupport
    with ApplicationStateUtil
    with NoMetricsGuiceOneAppPerSuite
    with CollaboratorTestData {

  implicit val m: Materializer                    = app.materializer
  implicit val instantFormatters: Format[Instant] = MongoJavatimeFormats.instantFormat
  implicit val dateFormatters: Format[LocalDate]  = MongoJavatimeFormats.localDateFormat
  implicit val metrics: Metrics                   = app.injector.instanceOf[Metrics]

  val applicationRepository = new ApplicationRepository(mongoComponent, metrics)

  override protected def beforeEach(): Unit = {
    await(mongoDatabase.drop().toFuture())
  }

  trait Setup {
    val lockKeeperSuccess: () => Boolean         = () => true
    val mongoLockRepository: MongoLockRepository = app.injector.instanceOf[MongoLockRepository]

    val mockResetLastAccessDateJobLockService: ResetLastAccessDateJobLockService =
      new ResetLastAccessDateJobLockService(mongoLockRepository) {
        override val ttl: Duration = 1.minutes

        override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
          if (lockKeeperSuccess()) body.map(value => Some(value))(ec) else Future.successful(None)
      }
  }

  trait DryRunSetup extends Setup {
    val dateToSet: LocalDate                    = LocalDate.of(2019, 6, 1)
    val jobConfig: ResetLastAccessDateJobConfig = ResetLastAccessDateJobConfig(dateToSet, enabled = true, dryRun = true)
    val underTest                               = new ResetLastAccessDateJob(mockResetLastAccessDateJobLockService, applicationRepository, jobConfig)
  }

  object DryRunSetup {}

  trait ModifyDatesSetup extends Setup {
    val dateToSet: LocalDate = LocalDate.of(2019, 7, 10)

    val jobConfig: ResetLastAccessDateJobConfig = ResetLastAccessDateJobConfig(dateToSet, enabled = true, dryRun = false)
    val underTest                               = new ResetLastAccessDateJob(mockResetLastAccessDateJobLockService, applicationRepository, jobConfig)
  }

  "ResetLastAccessDateJob" should {
    "update lastAccess fields in database so that none pre-date the specified date" in new ModifyDatesSetup {

      val bulkInsert = List(
        anApplicationData(instantTS = dateToSet.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().truncatedTo(ChronoUnit.MILLIS)),
        anApplicationData(instantTS = dateToSet.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().truncatedTo(ChronoUnit.MILLIS)),
        anApplicationData(instantTS = dateToSet.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().truncatedTo(ChronoUnit.MILLIS))
      )
      await(Future.sequence(bulkInsert.map(i => applicationRepository.save(i))))

      await(underTest.runJob)

      val retrievedApplications: List[StoredApplication] = await(applicationRepository.fetchAll())

      retrievedApplications.size should be(3)
      retrievedApplications.foreach(app => {
        app.lastAccess.isDefined should be(true)
        app.lastAccess.get.isBefore(dateToSet.atStartOfDay(ZoneOffset.UTC).toInstant()) should be(false)
      })
    }

    "update lastAccess field on application when it does not have a lastAccessDate" in new ModifyDatesSetup {
      val applicationData: StoredApplication = anApplicationData(instantTS = dateToSet.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()).copy(lastAccess = None)

      await(applicationRepository.save(applicationData))
      await(underTest.runJob)

      val retrievedApplications: List[StoredApplication] = await(applicationRepository.fetchAll())

      retrievedApplications.size shouldBe 1
      retrievedApplications.head.lastAccess should not be None

    }

    "not update the database if dryRun option is specified" in new DryRunSetup {
      val application1: StoredApplication = anApplicationData(instantTS = dateToSet.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
      val application2: StoredApplication = anApplicationData(instantTS = dateToSet.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant())
      val application3: StoredApplication = anApplicationData(instantTS = dateToSet.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant())

      def inDbList(appId: ApplicationId): Option[StoredApplication] = retrievedApplications.find(_.id == appId)
      def epochMillisInDb(appId: ApplicationId): Option[Long]       = inDbList(appId).flatMap(_.lastAccess).map(_.toEpochMilli())

      await(Future.sequence(List(application1, application2, application3).map(applicationRepository.save)))

      await(underTest.runJob)

      val retrievedApplications: List[StoredApplication] = await(applicationRepository.fetchAll())
      retrievedApplications.size should be(3)
      epochMillisInDb(application1.id) shouldBe application1.lastAccess.map(_.toEpochMilli())
      epochMillisInDb(application2.id) shouldBe application2.lastAccess.map(_.toEpochMilli())
      epochMillisInDb(application3.id) shouldBe application3.lastAccess.map(_.toEpochMilli())
    }
  }

  def anApplicationData(
      id: ApplicationId = ApplicationId.random,
      instantTS: Instant
    ): StoredApplication = {
    StoredApplication(
      id,
      ApplicationName(s"myApp-${id}"),
      s"myapp-${id}",
      Set("user@example.com".admin()),
      Some(CoreApplicationData.appDescription),
      "myapplication",
      ApplicationTokens(
        StoredToken(ClientId.random, "ccc")
      ),
      testingState(),
      Access.Standard(),
      instantTS,
      lastAccess = Some(instantTS)
    )
  }
}
