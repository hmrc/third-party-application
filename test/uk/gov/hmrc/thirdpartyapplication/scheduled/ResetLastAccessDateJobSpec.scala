/*
 * Copyright 2021 HM Revenue & Customs
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

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import org.joda.time.{DateTime, Duration, LocalDate}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationState, Collaborator, EnvironmentToken, Role, Standard, State}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.models.UserId

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationId

class ResetLastAccessDateJobSpec extends AsyncHmrcSpec with MongoSpecSupport with BeforeAndAfterEach with BeforeAndAfterAll with ApplicationStateUtil {

  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  trait Setup {
    val lockKeeperSuccess: () => Boolean = () => true

    val mockLockKeeper = new ResetLastAccessDateJobLockKeeper(reactiveMongoComponent) {

      //noinspection ScalaStyle
      override def lockId: String = null

      //noinspection ScalaStyle
      override def repo: LockRepository = null

      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number

      override def tryLock[T](body: => Future[T])(implicit ec : ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))
        else Future.successful(None)
    }
  }

  import scala.concurrent.ExecutionContext.Implicits.global
  val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  override def beforeEach() {
    applicationRepository.drop
  }

  override protected def afterAll() {
    applicationRepository.drop
  }


  trait DryRunSetup extends Setup {
    import scala.concurrent.ExecutionContext.Implicits.global

    val dateToSet = new LocalDate(2019, 6, 1)
    val jobConfig = ResetLastAccessDateJobConfig(dateToSet, enabled = true, dryRun = true)
    val underTest = new ResetLastAccessDateJob(mockLockKeeper, applicationRepository, jobConfig)
  }

  trait ModifyDatesSetup extends Setup {
    import scala.concurrent.ExecutionContext.Implicits.global
    val dateToSet = new LocalDate(2019, 7, 10)

    val jobConfig = ResetLastAccessDateJobConfig(dateToSet, enabled = true, dryRun = false)
    val underTest = new ResetLastAccessDateJob(mockLockKeeper, applicationRepository, jobConfig)
  }

  "ResetLastAccessDateJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "update lastAccess fields in database so that none pre-date the specified date" in new ModifyDatesSetup {
      await(applicationRepository.bulkInsert(
        Seq(
          anApplicationData(lastAccessDate = dateToSet.minusDays(1).toDateTimeAtCurrentTime),
          anApplicationData(lastAccessDate = dateToSet.minusDays(2).toDateTimeAtCurrentTime),
          anApplicationData(lastAccessDate = dateToSet.plusDays(3).toDateTimeAtCurrentTime))))


      await(underTest.runJob)

      val retrievedApplications: List[ApplicationData] = await(applicationRepository.fetchAll())
      retrievedApplications.size should be (3)
      retrievedApplications.foreach(app => {
        app.lastAccess.isDefined should be (true)
        app.lastAccess.get.isBefore(dateToSet.toDateTimeAtStartOfDay) should be (false)
      })
    }

    "not update the database if dryRun option is specified" in new DryRunSetup {
      val application1 = anApplicationData(lastAccessDate = dateToSet.minusDays(1).toDateTimeAtCurrentTime)
      val application2 = anApplicationData(lastAccessDate = dateToSet.minusDays(2).toDateTimeAtCurrentTime)
      val application3 = anApplicationData(lastAccessDate = dateToSet.plusDays(3).toDateTimeAtCurrentTime)

      await(applicationRepository.bulkInsert(Seq(application1, application2, application3)))

      await(underTest.runJob)

      val retrievedApplications: List[ApplicationData] = await(applicationRepository.fetchAll())
      retrievedApplications.size should be (3)
      retrievedApplications.find(_.id == application1.id).get.lastAccess.get.isEqual(application1.lastAccess.get) should be (true)
      retrievedApplications.find(_.id == application2.id).get.lastAccess.get.isEqual(application2.lastAccess.get) should be (true)
      retrievedApplications.find(_.id == application3.id).get.lastAccess.get.isEqual(application3.lastAccess.get) should be (true)
    }
  }

  def anApplicationData(id: ApplicationId = ApplicationId.random(), lastAccessDate: DateTime): ApplicationData = {
    ApplicationData(
      id,
      s"myApp-${id.value}",
      s"myapp-${id.value}",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR, UserId.random)),
      Some("description"),
      "myapplication",
      ApplicationTokens(
        EnvironmentToken(UUID.randomUUID().toString, "ccc")
      ),
      ApplicationState(State.PRODUCTION),
      Standard(List.empty, None, None),
      lastAccessDate,
      Some(lastAccessDate))
  }
}
