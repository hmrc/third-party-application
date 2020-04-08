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

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.Duration
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{UpdateClientSecretNamesJob, UpdateClientSecretNamesJobLockKeeper}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


class UpdateClientSecretNamesJobSpec extends AsyncHmrcSpec with MongoSpecSupport with ApplicationStateUtil with BeforeAndAfterEach with BeforeAndAfterAll {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }
  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()
  val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  trait Setup {
    val lockKeeperSuccess: () => Boolean = () => true
    val mockLockKeeper: UpdateClientSecretNamesJobLockKeeper = new UpdateClientSecretNamesJobLockKeeper(reactiveMongoComponent) {
      //noinspection ScalaStyle
      override def lockId: String = null
      //noinspection ScalaStyle
      override def repo: LockRepository = null
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))
        else Future.successful(None)
    }

    val configuredWorkFactor = 5
    val underTest = new UpdateClientSecretNamesJob(mockLockKeeper, applicationRepository)
  }

  override def beforeEach() {
    await(applicationRepository.drop)
  }

  override protected def afterAll() {
    await(applicationRepository.drop)
  }

  "UpdateClientSecretNamesJob" should {
    def namedClientSecret(id: String, name: String): ClientSecret =
      ClientSecret(id = id, name = name, secret = UUID.randomUUID().toString, hashedSecret = "hashed-secret")

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

    def updatedClientSecret(application: ApplicationData, clientSecretId: String): ClientSecret =
      application.tokens.production.clientSecrets.find(_.id == clientSecretId).get

    def expectedName(clientSecret: ClientSecret) = clientSecret.secret.takeRight(4)

    "update client secret names set as empty string" in new Setup {
      private val applicationId = UUID.randomUUID()
      private val clientSecretId = UUID.randomUUID().toString
      private val existingClientSecret = namedClientSecret(clientSecretId, "")

      await(applicationRepository.save(anApplicationData(applicationId, List(existingClientSecret))))

      await(underTest.execute)

      val updatedApplication: ApplicationData = await(applicationRepository.fetch(applicationId)).get

      updatedClientSecret(updatedApplication, clientSecretId).name should equal (expectedName(existingClientSecret))
    }

    "update client secret names set as Default" in new Setup {
      private val applicationId = UUID.randomUUID()
      private val clientSecretId = UUID.randomUUID().toString
      private val existingClientSecret = namedClientSecret(clientSecretId, "Default")

      await(applicationRepository.save(anApplicationData(applicationId, List(existingClientSecret))))

      await(underTest.execute)

      val updatedApplication: ApplicationData = await(applicationRepository.fetch(applicationId)).get

      updatedClientSecret(updatedApplication, clientSecretId).name should equal (expectedName(existingClientSecret))
    }

    "update client secret names set with masked values" in new Setup {
      private val applicationId = UUID.randomUUID()
      private val clientSecretId = UUID.randomUUID().toString
      private val existingClientSecret = namedClientSecret(clientSecretId, "••••••••••••••••••••••••••••••••abc1")

      await(applicationRepository.save(anApplicationData(applicationId, List(existingClientSecret))))

      await(underTest.execute)

      val updatedApplication: ApplicationData = await(applicationRepository.fetch(applicationId)).get

      updatedClientSecret(updatedApplication, clientSecretId).name should equal (expectedName(existingClientSecret))
    }

    "update client secret names for all applications" in new Setup {
      def allNamesUpdated(clientSecrets: Seq[ClientSecret]) = {
        clientSecrets foreach { cs =>
          cs.name should be (expectedName(cs))
        }
      }

      private val application1 = anApplicationData(UUID.randomUUID(), List.fill(5)(namedClientSecret(UUID.randomUUID().toString, "application-1-secret")))
      private val application2 = anApplicationData(UUID.randomUUID(), List.fill(5)(namedClientSecret(UUID.randomUUID().toString, "application-2-secret")))
      private val application3 = anApplicationData(UUID.randomUUID(), List.fill(5)(namedClientSecret(UUID.randomUUID().toString, "application-3-secret")))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      await(underTest.execute)

      val updatedApplication1: ApplicationData = await(applicationRepository.fetch(application1.id)).get
      val updatedApplication2: ApplicationData = await(applicationRepository.fetch(application2.id)).get
      val updatedApplication3: ApplicationData = await(applicationRepository.fetch(application3.id)).get

      allNamesUpdated(updatedApplication1.tokens.production.clientSecrets)
      allNamesUpdated(updatedApplication2.tokens.production.clientSecrets)
      allNamesUpdated(updatedApplication3.tokens.production.clientSecrets)

    }
  }

}
