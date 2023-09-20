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

import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}

import akka.stream.Materializer
import com.kenshoo.play.metrics.Metrics

import play.api.libs.json.Format
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, CollaboratorTestData, NoMetricsGuiceOneAppPerSuite}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ClientId

class ResetLastAccessDateJobSpec
    extends AsyncHmrcSpec
    with MongoSupport
    with CleanMongoCollectionSupport
    with ApplicationStateUtil
    with NoMetricsGuiceOneAppPerSuite
    with CollaboratorTestData {

  implicit val m: Materializer                           = app.materializer
  implicit val dateTimeFormatters: Format[LocalDateTime] = MongoJavatimeFormats.localDateTimeFormat
  implicit val dateFormatters: Format[LocalDate]         = MongoJavatimeFormats.localDateFormat
  implicit val metrics                                   = app.injector.instanceOf[Metrics]

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
    val dateToSet: LocalDate                    = LocalDateTime.of(2019, 6, 1, 0, 0).toLocalDate
    val jobConfig: ResetLastAccessDateJobConfig = ResetLastAccessDateJobConfig(dateToSet, enabled = true, dryRun = true)
    val underTest                               = new ResetLastAccessDateJob(mockResetLastAccessDateJobLockService, applicationRepository, jobConfig)
  }

  trait ModifyDatesSetup extends Setup {
    val dateToSet: LocalDate = LocalDateTime.of(2019, 7, 10, 0, 0).toLocalDate

    val jobConfig: ResetLastAccessDateJobConfig = ResetLastAccessDateJobConfig(dateToSet, enabled = true, dryRun = false)
    val underTest                               = new ResetLastAccessDateJob(mockResetLastAccessDateJobLockService, applicationRepository, jobConfig)
  }

  "ResetLastAccessDateJob" should {
    "update lastAccess fields in database so that none pre-date the specified date" in new ModifyDatesSetup {

      val bulkInsert = List(
        anApplicationData(localDateTime = dateToSet.minusDays(1).atStartOfDay()),
        anApplicationData(localDateTime = dateToSet.minusDays(2).atStartOfDay()),
        anApplicationData(localDateTime = dateToSet.plusDays(3).atStartOfDay())
      )
      await(Future.sequence(bulkInsert.map(i => applicationRepository.save(i))))

      await(underTest.runJob)

      val retrievedApplications: List[ApplicationData] = await(applicationRepository.fetchAll())

      retrievedApplications.size should be(3)
      retrievedApplications.foreach(app => {
        app.lastAccess.isDefined should be(true)
        app.lastAccess.get.isBefore(dateToSet.atStartOfDay()) should be(false)
      })
    }

    "update lastAccess field on application when it does not have a lastAccessDate" in new ModifyDatesSetup {
      val applicationData: ApplicationData = anApplicationData(localDateTime = dateToSet.minusDays(1).atStartOfDay()).copy(lastAccess = None)

      await(applicationRepository.save(applicationData))
      await(underTest.runJob)

      val retrievedApplications: List[ApplicationData] = await(applicationRepository.fetchAll())

      retrievedApplications.size shouldBe 1
      retrievedApplications.head.lastAccess should not be None

    }

    "not update the database if dryRun option is specified" in new DryRunSetup {
      val application1: ApplicationData = anApplicationData(localDateTime = dateToSet.minusDays(1).atStartOfDay())
      val application2: ApplicationData = anApplicationData(localDateTime = dateToSet.minusDays(2).atStartOfDay())
      val application3: ApplicationData = anApplicationData(localDateTime = dateToSet.plusDays(3).atStartOfDay())

      await(Future.sequence(List(application1, application2, application3).map(applicationRepository.save)))

      await(underTest.runJob)

      val retrievedApplications: List[ApplicationData] = await(applicationRepository.fetchAll())
      retrievedApplications.size should be(3)
      retrievedApplications.find(_.id == application1.id).get.lastAccess.get.isEqual(application1.lastAccess.get) should be(true)
      retrievedApplications.find(_.id == application2.id).get.lastAccess.get.isEqual(application2.lastAccess.get) should be(true)
      retrievedApplications.find(_.id == application3.id).get.lastAccess.get.isEqual(application3.lastAccess.get) should be(true)
    }
  }

  def anApplicationData(id: ApplicationId = ApplicationId.random, localDateTime: LocalDateTime): ApplicationData = {
    ApplicationData(
      id,
      s"myApp-${id.value}",
      s"myapp-${id.value}",
      Set("user@example.com".admin()),
      Some("description"),
      "myapplication",
      ApplicationTokens(
        Token(ClientId.random, "ccc")
      ),
      testingState(),
      Standard(),
      localDateTime,
      lastAccess = Some(localDateTime)
    )
  }
}
