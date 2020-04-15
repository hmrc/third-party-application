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

package it.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.{DateTime, Duration}
import org.scalatest.BeforeAndAfterEach
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{DeleteUnusedApplicationFieldsJob, DeleteUnusedApplicationFieldsJobLockKeeper}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class DeleteUnusedApplicationFieldsJobSpec extends AsyncHmrcSpec with MongoSpecSupport with BeforeAndAfterEach with ApplicationStateUtil {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()
  val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  trait Setup {
    val lockKeeperSuccess: () => Boolean = () => true
    val mockLockKeeper: DeleteUnusedApplicationFieldsJobLockKeeper = new DeleteUnusedApplicationFieldsJobLockKeeper(reactiveMongoComponent) {
      //noinspection ScalaStyle
      override def lockId: String = null
      //noinspection ScalaStyle
      override def repo: LockRepository = null
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))
        else Future.successful(None)
    }

    val underTest = new DeleteUnusedApplicationFieldsJob(mockLockKeeper, applicationRepository)
  }

  override def beforeEach() {
    await(applicationRepository.drop)
  }

  override protected def afterEach() {
    await(applicationRepository.drop)
  }

  "DeleteUnusedApplicationFieldsJob" should {
    def createApplication(applicationId: UUID, clientSecrets: List[ClientSecret]): Future[ApplicationData] =
      applicationRepository.save(
        ApplicationData(
          applicationId,
          s"myApp-$applicationId",
          s"myapp-$applicationId",
          Set(Collaborator("user@example.com", Role.ADMINISTRATOR)),
          Some("description"),
          "myapplication",
          ApplicationTokens(
            EnvironmentToken("aaa", "ccc", clientSecrets)
          ),
          testingState(),
          Standard(List.empty, None, None),
          HmrcTime.now,
          Some(HmrcTime.now)))

    def aClientSecret(clientSecretId: String): ClientSecret =
      ClientSecret(name = clientSecretId.take(4), createdOn = DateTime.now, id = clientSecretId, hashedSecret = "hashed-secret")

    def addFieldToApplication(applicationId: UUID, fieldName: String, fieldValue: String) =
      applicationRepository.updateApplication(applicationId, Json.obj("$set" -> Json.obj(fieldName -> fieldValue)))

    def addSandboxToken(applicationId: UUID) =
      applicationRepository.updateApplication(
        applicationId,
        Json.obj(
          "$set" ->
            Json.obj(
              "tokens.sandbox" ->
                Json.obj("clientId" -> UUID.randomUUID(), "accessToken" -> UUID.randomUUID(), "clientSecrets" -> Json.arr()))))

    def addSecretFieldToClientSecret(applicationId: UUID, clientSecretId: String) =
      applicationRepository.updateClientSecretField(applicationId, clientSecretId, "secret", UUID.randomUUID().toString)

    def fieldExistsInApplication(applicationId: UUID, fieldName: String): Future[Boolean] =
      applicationRepository.fetchWithProjection(
        Json.obj("id" -> applicationId, fieldName -> Json.obj("$exists" -> true)),
        Json.obj("_id" -> 0, "id" -> 1, fieldName -> 1))
        .map(_.size == 1)

    def verifyFieldsExistInApplication(applicationId: UUID, fieldNames: Seq[String]) =
      fieldNames.foreach(
        fieldName => await(fieldExistsInApplication(applicationId, fieldName)) should be (true))

    def verifyFieldsDoNotExistInApplication(applicationId: UUID, fieldNames: Seq[String]) =
      fieldNames.foreach(
        fieldName => await(fieldExistsInApplication(applicationId, fieldName)) should be (false))

    def verifyUnusedFieldsDoNotExistInApplication(applicationId: UUID) =
      verifyFieldsDoNotExistInApplication(applicationId, Seq("wso2Username", "wso2Password", "tokens.sandbox"))

    def verifySecretFieldDoesNotExistInClientSecrets(applicationId: UUID, numberOfClientSecrets: Int) = {
      val clientSecretFields: Seq[String] = 0 until numberOfClientSecrets map (i => s"tokens.production.clientSecrets.$i.secret")
      verifyFieldsDoNotExistInApplication(applicationId, clientSecretFields)
    }

    "delete wso2Username field" in new Setup {
      private val applicationId = UUID.randomUUID()

      await(createApplication(applicationId, List.empty))
      await(addFieldToApplication(applicationId, "wso2Username", "abcd1234"))

      verifyFieldsExistInApplication(applicationId, Seq("wso2Username"))

      await(underTest.execute)

      verifyUnusedFieldsDoNotExistInApplication(applicationId)
    }

    "delete wso2Password field" in new Setup {
      private val applicationId = UUID.randomUUID()

      await(createApplication(applicationId, List.empty))
      await(addFieldToApplication(applicationId, "wso2Password", "abcd1234"))

      verifyFieldsExistInApplication(applicationId, Seq("wso2Password"))

      await(underTest.execute)

      verifyUnusedFieldsDoNotExistInApplication(applicationId)
    }

    "delete sandbox token sub-document" in new Setup {
      private val applicationId = UUID.randomUUID()

      await(createApplication(applicationId, List.empty))
      await(addSandboxToken(applicationId))

      verifyFieldsExistInApplication(applicationId, Seq("tokens.sandbox"))

      await(underTest.execute)

      verifyUnusedFieldsDoNotExistInApplication(applicationId)
    }

    "delete all secret fields in client secrets" in new Setup {
      private val applicationId = UUID.randomUUID()
      private val clientSecret1Id = UUID.randomUUID().toString
      private val clientSecret2Id = UUID.randomUUID().toString
      private val clientSecret3Id = UUID.randomUUID().toString

      await(createApplication(applicationId, List(aClientSecret(clientSecret1Id), aClientSecret(clientSecret2Id), aClientSecret(clientSecret3Id))))
      await(addSecretFieldToClientSecret(applicationId, clientSecret1Id))
      await(addSecretFieldToClientSecret(applicationId, clientSecret2Id))
      await(addSecretFieldToClientSecret(applicationId, clientSecret3Id))

      verifyFieldsExistInApplication(
        applicationId,
        Seq("tokens.production.clientSecrets.0.secret", "tokens.production.clientSecrets.1.secret", "tokens.production.clientSecrets.2.secret"))

      await(underTest.execute)

      verifyUnusedFieldsDoNotExistInApplication(applicationId)
      verifySecretFieldDoesNotExistInClientSecrets(applicationId, 3)
    }

    "delete all the things" in new Setup {
      private val applicationId = UUID.randomUUID()
      private val clientSecret1Id = UUID.randomUUID().toString

      await(createApplication(applicationId, List(aClientSecret(clientSecret1Id))))
      await(addFieldToApplication(applicationId, "wso2Username", "abcd1234"))
      await(addFieldToApplication(applicationId, "wso2Password", "abcd1234"))
      await(addSandboxToken(applicationId))
      await(addSecretFieldToClientSecret(applicationId, clientSecret1Id))

      verifyFieldsExistInApplication(applicationId, Seq("wso2Username", "wso2Password", "tokens.sandbox", "tokens.production.clientSecrets.0.secret"))

      await(underTest.execute)

      verifyUnusedFieldsDoNotExistInApplication(applicationId)
      verifySecretFieldDoesNotExistInClientSecrets(applicationId, 1)
    }
  }
}
