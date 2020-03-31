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
import com.github.t3hnar.bcrypt._
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.Duration
import org.scalatest.{Assertion, BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{HashExistingClientSecretsJob, HashExistingClientSecretsJobLockKeeper}
import uk.gov.hmrc.thirdpartyapplication.services.{ClientSecretService, ClientSecretServiceConfig}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


class HashExistingClientSecretsJobSpec extends AsyncHmrcSpec with MongoSpecSupport with ApplicationStateUtil with BeforeAndAfterEach with BeforeAndAfterAll {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }
  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()
  val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  trait Setup {
    val lockKeeperSuccess: () => Boolean = () => true
    val mockLockKeeper: HashExistingClientSecretsJobLockKeeper = new HashExistingClientSecretsJobLockKeeper(reactiveMongoComponent) {
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
    val underTest = new HashExistingClientSecretsJob(mockLockKeeper, new ClientSecretService(ClientSecretServiceConfig(configuredWorkFactor)), applicationRepository)
  }

  override def beforeEach() {
    await(applicationRepository.drop)
  }

  override protected def afterAll() {
    await(applicationRepository.drop)
  }

  "HashExistingClientSecretsJob" should {
    def hashIsValid(clientSecret: ClientSecret): Assertion = {
      val hashCheck = clientSecret.secret.isBcryptedSafe(clientSecret.hashedSecret)
      hashCheck.isSuccess should be (true)
      hashCheck.get should be (true)
    }

    "update hash where the work factor has changed" in new Setup {
      private val applicationId = UUID.randomUUID()
      private val secret = "foo"
      private val oldHash = secret.bcrypt(configuredWorkFactor + 1)
      private val clientSecretWithOldHash = ClientSecret("secret-1", secret, hashedSecret = oldHash)
      private val application = anApplicationData(applicationId, List(clientSecretWithOldHash))

      await(applicationRepository.save(application))

      await(underTest.execute)

      val updatedApplication: ApplicationData = await(applicationRepository.fetch(applicationId)).get
      val updatedClientSecret: ClientSecret = updatedApplication.tokens.production.clientSecrets.find(_.name == "secret-1").get

      updatedClientSecret.hashedSecret should not equal oldHash
      hashIsValid(updatedClientSecret)
    }

    "ignore hashes where the work factor has not changed" in new Setup {
      private val applicationId = UUID.randomUUID()
      private val secret = "foo"
      private val existingHash = secret.bcrypt(configuredWorkFactor)
      private val application = anApplicationData(applicationId, List(ClientSecret("secret-1", secret, hashedSecret = existingHash)))

      await(applicationRepository.save(application))

      await(underTest.execute)

      val updatedApplication: ApplicationData = await(applicationRepository.fetch(applicationId)).get
      val updatedClientSecret: ClientSecret = updatedApplication.tokens.production.clientSecrets.find(_.name == "secret-1").get

      updatedClientSecret.hashedSecret should equal (existingHash)
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
