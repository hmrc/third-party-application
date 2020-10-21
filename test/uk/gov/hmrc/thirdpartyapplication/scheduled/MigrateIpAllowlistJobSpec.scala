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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import org.joda.time.Duration
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.libs.json.{JsObject, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.play.json.ImplicitBSONHandlers.JsObjectDocumentWriter
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import scala.concurrent.{ExecutionContext, Future}

class MigrateIpAllowlistJobSpec extends AsyncHmrcSpec with MongoSpecSupport with BeforeAndAfterEach with BeforeAndAfterAll with ApplicationStateUtil {

  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  trait Setup {
    val lockKeeperSuccess: () => Boolean = () => true
    val mockLockKeeper = new MigrateIpAllowlistJobLockKeeper(reactiveMongoComponent) {
      //noinspection ScalaStyle
      override def lockId: String = null
      //noinspection ScalaStyle
      override def repo: LockRepository = null
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec : ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))
        else Future.successful(None)
    }

    val jobConfig: MigrateIpAllowlistJobConfig = MigrateIpAllowlistJobConfig(enabled = true)
    val underTest = new MigrateIpAllowlistJob(mockLockKeeper, applicationRepository, jobConfig)
  }

  import scala.concurrent.ExecutionContext.Implicits.global
  val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  override def beforeEach() {
    applicationRepository.drop
  }

  override protected def afterAll() {
    applicationRepository.drop
  }

  "MigrateIpAllowlistJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "migrate ipWhitelist to allowlist when present in the app" in new Setup {
      await(applicationRepository.collection.insert(false).one(Json.parse(applicationWithNoIpWhitelist).as[JsObject]))
      await(applicationRepository.collection.insert(false).one(Json.parse(applicationWithIpWhitelist).as[JsObject]))

      val result: underTest.Result = await(underTest.execute)

      result.message shouldBe "MigrateIpAllowlistJob Job ran successfully."

      val Some(updatedAppWithNoAllowlist) = await(applicationRepository.collection.find[JsObject, JsObject](Json.obj("name" -> "app with no ipWhitelist")).one[JsObject])
      (updatedAppWithNoAllowlist \ "ipAllowlist" \ "required").as[Boolean] shouldBe false
      (updatedAppWithNoAllowlist \ "ipAllowlist" \ "allowlist").as[Set[String]] shouldBe empty

      val Some(updatedAppWithAllowlist) = await(applicationRepository.collection.find[JsObject, JsObject](Json.obj("name" -> "app with ipWhitelist")).one[JsObject])
      (updatedAppWithAllowlist \ "ipAllowlist" \ "required").as[Boolean] shouldBe false
      (updatedAppWithAllowlist \ "ipAllowlist" \ "allowlist").as[Set[String]] shouldBe Set("80.80.91.1/24", "80.80.92.1/24")
    }

    "return a failed result when the job fails" in new Setup {
      await(applicationRepository.collection.insert(false).one(Json.obj("foo" -> "bar")))

      val result: underTest.Result = await(underTest.execute)

      result.message should include ("The execution of scheduled job MigrateIpAllowlistJob failed")
    }
  }

  def applicationWithNoIpWhitelist: String =
    """
      |{
      |    "id" : "9ea93a36-5cdb-45aa-86e1-9ed5483ebbfa",
      |    "name" : "app with no ipWhitelist",
      |    "normalisedName" : "app with no ipWhitelist",
      |    "collaborators" : [],
      |    "wso2ApplicationName" : "app with no ipWhitelist",
      |    "tokens" : {
      |        "production" : {
      |            "clientId" : "dummyId",
      |            "accessToken" : "dummyValue",
      |            "clientSecrets" : []
      |        }
      |    },
      |    "state" : {
      |        "name" : "PRODUCTION",
      |        "updatedOn" : {"$date": 1603296213681}
      |    },
      |    "access" : {
      |        "accessType" : "STANDARD",
      |        "overrides" : [],
      |        "redirectUris" : []
      |    },
      |    "createdOn" : {"$date": 1603296213681},
      |    "environment" : "PRODUCTION",
      |    "blocked" : false
      |}
  """.stripMargin

  def applicationWithIpWhitelist: String =
    """
      |{
      |    "id" : "9ea93a36-5cdb-45aa-86e1-9ed5483ebbfb",
      |    "name" : "app with ipWhitelist",
      |    "normalisedName" : "app with ipWhitelist",
      |    "collaborators" : [],
      |    "wso2ApplicationName" : "app with ipWhitelist",
      |    "tokens" : {
      |        "production" : {
      |            "clientId" : "dummyId",
      |            "accessToken" : "dummyValue",
      |            "clientSecrets" : []
      |        }
      |    },
      |    "state" : {
      |        "name" : "PRODUCTION",
      |        "updatedOn" : {"$date": 1603296213681}
      |    },
      |    "access" : {
      |        "accessType" : "STANDARD",
      |        "overrides" : [],
      |        "redirectUris" : []
      |    },
      |    "createdOn" : {"$date": 1603296213681},
      |    "environment" : "PRODUCTION",
      |    "blocked" : false,
      |    "ipWhitelist" : [
      |        "80.80.91.1/24",
      |        "80.80.92.1/24"
      |    ]
      |}
  """.stripMargin
}
