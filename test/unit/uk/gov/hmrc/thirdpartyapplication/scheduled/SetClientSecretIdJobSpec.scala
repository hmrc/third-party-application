/*
 * Copyright 2020 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.UUID
import java.util.concurrent.TimeUnit.{HOURS, SECONDS}

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.Duration
import org.mockito.Mockito
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{SetClientSecretIdJob, SetClientSecretIdJobConfig, SetClientSecretIdJobLockKeeper}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class SetClientSecretIdJobSpec extends AsyncHmrcSpec with MongoSpecSupport with ApplicationStateUtil with BeforeAndAfterEach with BeforeAndAfterAll {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }
  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()
  val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  trait Setup {
    val lockKeeperSuccess: () => Boolean = () => true
    val mockLockKeeper: SetClientSecretIdJobLockKeeper = new SetClientSecretIdJobLockKeeper(reactiveMongoComponent) {
      //noinspection ScalaStyle
      override def lockId: String = null
      //noinspection ScalaStyle
      override def repo: LockRepository = null
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))
        else Future.successful(None)
    }

    val initialDelay: FiniteDuration = FiniteDuration(60, SECONDS) // scalastyle:off magic.number
    val interval: FiniteDuration = FiniteDuration(24, HOURS) // scalastyle:off magic.number
    val config: SetClientSecretIdJobConfig = SetClientSecretIdJobConfig(initialDelay, interval, enabled = true)

    val underTest = new SetClientSecretIdJob(mockLockKeeper, config, applicationRepository)
  }

  trait SetupWithRepositorySpy extends Setup {
    val applicationRepositorySpy: ApplicationRepository = Mockito.spy(applicationRepository)

    override val underTest = new SetClientSecretIdJob(mockLockKeeper, config, applicationRepositorySpy)
  }

  override def beforeEach() {
    await(applicationRepository.drop)
  }

  override protected def afterAll() {
    await(applicationRepository.drop)
  }

  "SetClientSecretIdJob" should {
    "add a client secret ID only to the client secrets that do not have an ID yet" in new Setup {
      private val clientSecretWithId = ClientSecret("", id = Some(UUID.randomUUID().toString))
      private val clientSecretWithoutId = ClientSecret("", id = None)
      private val application = anApplicationData(UUID.randomUUID(), List(clientSecretWithId, clientSecretWithoutId))
      await(applicationRepository.save(application))

      await(underTest.execute)

      val updatedApps: List[ApplicationData] = await(applicationRepository.fetchAll())

      updatedApps should have size 1
      val firstClientSecret: ClientSecret = updatedApps.head.tokens.production.clientSecrets.head
      val secondClientSecret: ClientSecret = updatedApps.head.tokens.production.clientSecrets(1)

      firstClientSecret shouldBe clientSecretWithId
      secondClientSecret should have (
        'name (clientSecretWithoutId.name),
        'secret (clientSecretWithoutId.secret),
        'createdOn (clientSecretWithoutId.createdOn),
        'lastAccess (clientSecretWithoutId.lastAccess)
      )
      secondClientSecret.id should not be empty
    }

    "add client secret IDs to multiple applications" in new Setup {
      private val application1 = anApplicationData(UUID.randomUUID(), List(ClientSecret("", id = None)))
      private val application2 = anApplicationData(UUID.randomUUID(), List(ClientSecret("", id = None)))
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(underTest.execute)

      val updatedApps: List[ApplicationData] = await(applicationRepository.fetchAll())

      updatedApps should have size 2
      val firstAppClientSecretId: Option[String] = updatedApps.head.tokens.production.clientSecrets.head.id
      val secondAppClientSecretId: Option[String] = updatedApps(1).tokens.production.clientSecrets.head.id
      firstAppClientSecretId should not be empty
      secondAppClientSecretId should not be empty
      firstAppClientSecretId should not be secondAppClientSecretId
    }

    "add a different ID to client secrets of the same application" in new Setup {
      private val application = anApplicationData(UUID.randomUUID(), List(ClientSecret("", id = None), ClientSecret("", id = None)))
      await(applicationRepository.save(application))

      await(underTest.execute)

      val updatedApps: List[ApplicationData] = await(applicationRepository.fetchAll())

      updatedApps should have size 1
      val firstClientSecretId: Option[String] = updatedApps.head.tokens.production.clientSecrets.head.id
      val secondClientSecretId: Option[String] = updatedApps.head.tokens.production.clientSecrets(1).id
      firstClientSecretId should not be empty
      secondClientSecretId should not be empty
      firstClientSecretId should not be secondClientSecretId
    }

    "not update the client secrets when all of them already have Ids" in new SetupWithRepositorySpy {
      private val application = anApplicationData(UUID.randomUUID(), List(ClientSecret("", id = Some("1")), ClientSecret("", id = Some("2"))))
      await(applicationRepository.save(application))

      await(underTest.execute)

      Mockito.verify(applicationRepositorySpy).processAll(*)
      Mockito.verify(applicationRepositorySpy).collection
      Mockito.verify(applicationRepositorySpy, atLeastOnce).mat
      Mockito.verify(applicationRepositorySpy, never).updateClientSecretId(*, *, *)
    }
  }

  def anApplicationData(id: UUID, clientSecrets: List[ClientSecret]): ApplicationData = {
    ApplicationData(
      id,
      s"myApp-$id",
      s"myapp-$id",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR)),
      Some("description"),
      "myapplication",
      ApplicationTokens(
        EnvironmentToken("aaa", "ccc", clientSecrets)
      ),
      testingState(),
      Standard(List.empty, None, None),
      HmrcTime.now,
      Some(HmrcTime.now))
  }
}
