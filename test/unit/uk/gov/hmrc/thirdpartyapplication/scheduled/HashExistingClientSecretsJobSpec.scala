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

    val underTest = new HashExistingClientSecretsJob(mockLockKeeper, new ClientSecretService(ClientSecretServiceConfig(5)), applicationRepository)
  }

  override def beforeEach() {
    await(applicationRepository.drop)
  }

  override protected def afterAll() {
    await(applicationRepository.drop)
  }

  "HashExistingClientSecretsJob" should {
    def hashIsValid(clientSecret: ClientSecret): Assertion = {
      clientSecret.hashedSecret.isDefined should be (true)

      val hashCheck = clientSecret.secret.isBcryptedSafe(clientSecret.hashedSecret.get)
      hashCheck.isSuccess should be (true)
      hashCheck.get should be (true)
    }

    "add a client secret hash only to the client secrets that do not have one yet" in new Setup {
      private val applicationId = UUID.randomUUID()

      private val clientSecretWithHash = ClientSecret("secret-1", "foo", hashedSecret = Some("hashed-foo"))
      private val clientSecretWithoutHash = ClientSecret("secret-2", "bar", hashedSecret = None)

      private val application = anApplicationData(applicationId, List(clientSecretWithHash, clientSecretWithoutHash))
      await(applicationRepository.save(application))

      await(underTest.execute)

      val updatedApplication: ApplicationData = await(applicationRepository.fetch(applicationId)).get

      val firstClientSecret: ClientSecret = updatedApplication.tokens.production.clientSecrets.find(_.name == "secret-1").get
      val secondClientSecret: ClientSecret = updatedApplication.tokens.production.clientSecrets.find(_.name == "secret-2").get

      firstClientSecret shouldBe clientSecretWithHash // Hash is not bcrypt-ed, so if job changed it, this will fail
      hashIsValid(secondClientSecret)
    }

    "add hashed secret to multiple applications" in new Setup {
      private val application1Id = UUID.randomUUID()
      private val application2Id = UUID.randomUUID()

      await(applicationRepository.save(anApplicationData(application1Id, List(ClientSecret("secret-1", hashedSecret = None)))))
      await(applicationRepository.save(anApplicationData(application2Id, List(ClientSecret("secret-2", hashedSecret = None)))))

      await(underTest.execute)

      val updatedApplication1: ApplicationData = await(applicationRepository.fetch(application1Id)).get
      val updatedApplication2: ApplicationData = await(applicationRepository.fetch(application2Id)).get

      hashIsValid(updatedApplication1.tokens.production.clientSecrets.head)
      hashIsValid(updatedApplication2.tokens.production.clientSecrets.head)
    }

    "add hashes to multiple client secrets of the same application" in new Setup {
      private val applicationId = UUID.randomUUID()

      await(applicationRepository.save(
        anApplicationData(
          applicationId,
          List(ClientSecret("secret-1", hashedSecret = None), ClientSecret("secret-2", hashedSecret = None), ClientSecret("secret-3", hashedSecret = None)))))

      await(underTest.execute)

      val updatedApplication: ApplicationData = await(applicationRepository.fetch(applicationId)).get

      updatedApplication.tokens.production.clientSecrets.foreach(hashIsValid)
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
