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

package uk.gov.hmrc.thirdpartyapplication.repository.mongo

import org.mongodb.scala.Document
import org.mongodb.scala.result.InsertOneResult
import org.scalatest.BeforeAndAfterEach
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsObject
import uk.gov.hmrc.apiplatform.modules.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.models.{StandardAccess => _}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, JavaDateTimeTestUtils, MetricsHelper}
import uk.gov.hmrc.utils.ServerBaseISpec

import java.time.{Clock, LocalDateTime}
import scala.util.Random.nextString

class ApplicationRepositorySerialisationISpec
    extends ServerBaseISpec
    with ApplicationTestData
    with JavaDateTimeTestUtils
    with ApplicationStateUtil
    with BeforeAndAfterEach
    with MetricsHelper
    with FixedClock
    with TestRawApplicationDocuments
    with MongoSupport {

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private val applicationRepository =
    app.injector.instanceOf[ApplicationRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop.toFuture())
    await(applicationRepository.ensureIndexes)
  }

  trait Setup {
    val applicationId           = ApplicationId.random
    lazy val defaultGrantLength = 547
    lazy val newGrantLength     = 1000

    private def generateAccessToken = {
      val lengthOfRandomToken = 5
      nextString(lengthOfRandomToken)
    }

    private def aClientSecret(id: ClientSecret.Id = ClientSecret.Id.random, name: String = "", lastAccess: Option[LocalDateTime] = None, hashedSecret: String = "hashed-secret") =
      ClientSecretData(
        id = id,
        name = name,
        lastAccess = lastAccess,
        hashedSecret = hashedSecret,
        createdOn = now
      )

    val applicationData = ApplicationData(
      applicationId,
      "appName",
      "normalised app name",
      Set(
        "user@example.com".admin()
      ),
      Some("description"),
      "myapplication",
      ApplicationTokens(
        Token(ClientId("aaa"), generateAccessToken, List(aClientSecret()))
      ),
      testingState(),
      Standard(),
      now,
      Some(now),
      grantLength = grantLength,
      checkInformation = None
    )

    def saveApplicationAsMongoJson(applicationAsRawJson: JsObject): InsertOneResult = {
      await(mongoDatabase.getCollection("application").insertOne(Document(applicationAsRawJson.toString())).toFuture())
    }
  }

  "repository" should {

    "create application with no allowAutoDelete but read it back as true" in new Setup {
      val rawJson: JsObject = applicationToMongoJson(applicationData, None)
      saveApplicationAsMongoJson(rawJson)
      val result            = await(applicationRepository.fetch(applicationId))

      result match {
        case Some(application) => {
          application.id mustBe applicationId
          application.allowAutoDelete mustBe true
        }
        case None              => fail()
      }
    }

    "create application with allowAutoDelete set to false and read it back correctly" in new Setup {
      val rawJson: JsObject = applicationToMongoJson(applicationData, Some(false))
      saveApplicationAsMongoJson(rawJson)
      val result            = await(applicationRepository.fetch(applicationId))

      result match {
        case Some(application) => {
          application.id mustBe applicationId
          application.allowAutoDelete mustBe false
        }
        case None              => fail()
      }
    }

    "create application with allowAutoDelete set to true and read it back correctly" in new Setup {
      val rawJson: JsObject = applicationToMongoJson(applicationData, Some(true))
      saveApplicationAsMongoJson(rawJson)
      val result            = await(applicationRepository.fetch(applicationId))

      result match {
        case Some(application) => {
          application.id mustBe applicationId
          application.allowAutoDelete mustBe true
        }
        case None              => fail()
      }
    }
  }

}
