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

import java.time.Clock
import scala.concurrent.ExecutionContext.Implicits.global

import org.mongodb.scala.Document
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters
import org.mongodb.scala.result.InsertOneResult
import org.scalatest.BeforeAndAfterEach

import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, JsPath, Json, __}
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationSearch, AutoDeleteAllowed, StandardAccess => _}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationRepositorySerialisationISpec
    extends ServerBaseISpec
    with JavaDateTimeTestUtils
    with StoredApplicationFixtures
    with CollaboratorTestData
    with BeforeAndAfterEach
    with MetricsHelper
    with FixedClock
    with TestRawApplicationDocuments
    with MongoSupport {

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "grantLengthInDays" -> 1,
        "metrics.jvm"       -> false,
        "mongodb.uri"       -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private val applicationRepository =
    app.injector.instanceOf[ApplicationRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(applicationRepository.ensureIndexes())
  }

  trait Setup {
    val applicationId           = ApplicationId.random
    lazy val defaultGrantLength = 547
    lazy val newGrantLength     = 1000

    val applicationData = storedApp.withId(applicationId).withState(appStateTesting).copy(refreshTokensAvailableFor = GrantLength.ONE_YEAR.period)

    def saveApplicationAsMongoJson(applicationAsRawJson: JsObject): InsertOneResult = {
      await(mongoDatabase.getCollection("application").insertOne(Document(applicationAsRawJson.toString())).toFuture())
    }

    def fetchApplicationRawJson(applicationId: ApplicationId): BsonDocument = {
      await(mongoDatabase.getCollection("application").find(Filters.equal("id", Codecs.toBson(applicationId))).headOption().map(_.value)).toBsonDocument()
    }

    def validateGrantLength(raw: BsonDocument, required: Boolean): Unit = {
      val path: JsPath = __ \ "grantLength"
      path(Json.parse(raw.toJson)) match {
        case Seq(x) => if (required) succeed else fail()
        case _      => if (required) fail() else succeed
      }
    }
  }

  "repository" should {

    "create application with no allowAutoDelete but read it back as true" in new Setup {
      val rawJson: JsObject = applicationToMongoJson(applicationData, allowAutoDelete = None)
      saveApplicationAsMongoJson(rawJson)
      val result            = await(applicationRepository.fetch(applicationId))

      result match {
        case Some(application) => {
          application.id mustBe applicationId
          application.allowAutoDelete mustBe true
          application.refreshTokensAvailableFor mustBe GrantLength.ONE_YEAR.period
        }
        case None              => fail()
      }

      val applicationSearch = new ApplicationSearch(filters = List(AutoDeleteAllowed))
      val appSearchResult   = await(applicationRepository.searchApplications("testing")(applicationSearch))

      appSearchResult.applications.size mustBe 1
      appSearchResult.applications.head.id mustBe applicationId
      appSearchResult.applications.head.allowAutoDelete mustBe true
      appSearchResult.applications.head.refreshTokensAvailableFor mustBe GrantLength.ONE_YEAR.period
    }

  }

  "create application with allowAutoDelete set to false and read it back correctly" in new Setup {
    val rawJson: JsObject = applicationToMongoJson(applicationData, allowAutoDelete = Some(false))
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
    val rawJson: JsObject = applicationToMongoJson(applicationData, allowAutoDelete = Some(true))
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

  "create application with invalid redirect UR in db and test we can read it back " in new Setup {
    val invalidUri        = new LoginRedirectUri("bobbins") // Using new to avoid validation of the apply method
    val data              = applicationData.withAccess(Access.Standard().copy(redirectUris = List(invalidUri)))
    val rawJson: JsObject = applicationToMongoJson(data, allowAutoDelete = Some(true))
    saveApplicationAsMongoJson(rawJson)
    val result            = await(applicationRepository.fetch(applicationId))

    result match {
      case Some(application) => {
        application.id mustBe applicationId
        application.allowAutoDelete mustBe true
        application.access match {
          case Access.Standard(redirectUris, _, _, _, _, _, _) => redirectUris.head mustBe invalidUri
          case _                                               => fail()
        }
      }
      case None              => fail()
    }
  }

  "create application with no grantLength or refreshTokensAvailableFor. refreshTokensAvailableFor is read back as default value " in new Setup {
    val rawJson: JsObject = applicationToMongoJson(applicationData, None, None, false)
    saveApplicationAsMongoJson(rawJson)
    val result            = await(applicationRepository.fetch(applicationId))

    result match {
      case Some(application) => {
        application.id mustBe applicationId
        application.allowAutoDelete mustBe true
        application.refreshTokensAvailableFor mustBe GrantLength.EIGHTEEN_MONTHS.period
      }
      case None              => fail()
    }

    val applicationSearch = new ApplicationSearch(filters = List(AutoDeleteAllowed))
    val appSearchResult   = await(applicationRepository.searchApplications("testing")(applicationSearch))

    appSearchResult.applications.size mustBe 1
    appSearchResult.applications.head.id mustBe applicationId
    appSearchResult.applications.head.allowAutoDelete mustBe true
    appSearchResult.applications.head.refreshTokensAvailableFor mustBe GrantLength.EIGHTEEN_MONTHS.period
  }

  "create application with grantLength 1 day but no refreshTokensAvailableFor. refreshTokensAvailableFor is read back as 1 day " in new Setup {
    val rawJson: JsObject = applicationToMongoJson(applicationData, None, Some(1), false)
    saveApplicationAsMongoJson(rawJson)
    val result            = await(applicationRepository.fetch(applicationId))

    result match {
      case Some(application) => {
        application.id mustBe applicationId
        application.allowAutoDelete mustBe true
        application.refreshTokensAvailableFor mustBe GrantLength.ONE_DAY.period
      }
      case None              => fail()
    }

    val applicationSearch = new ApplicationSearch(filters = List(AutoDeleteAllowed))
    val appSearchResult   = await(applicationRepository.searchApplications("testing")(applicationSearch))

    appSearchResult.applications.size mustBe 1
    appSearchResult.applications.head.id mustBe applicationId
    appSearchResult.applications.head.allowAutoDelete mustBe true
    appSearchResult.applications.head.refreshTokensAvailableFor mustBe GrantLength.ONE_DAY.period
  }

  "create application with no grantLength but refreshTokensAvailableFor 1 month. refreshTokensAvailableFor is read back as 1 month " in new Setup {
    val rawJson: JsObject = applicationToMongoJson(applicationData.copy(refreshTokensAvailableFor = GrantLength.ONE_MONTH.period), None, None, true)
    saveApplicationAsMongoJson(rawJson)
    val result            = await(applicationRepository.fetch(applicationId))

    result match {
      case Some(application) => {
        application.id mustBe applicationId
        application.allowAutoDelete mustBe true
        application.refreshTokensAvailableFor mustBe GrantLength.ONE_MONTH.period
      }
      case None              => fail()
    }

    val applicationSearch = new ApplicationSearch(filters = List(AutoDeleteAllowed))
    val appSearchResult   = await(applicationRepository.searchApplications("testing")(applicationSearch))

    appSearchResult.applications.size mustBe 1
    appSearchResult.applications.head.id mustBe applicationId
    appSearchResult.applications.head.allowAutoDelete mustBe true
    appSearchResult.applications.head.refreshTokensAvailableFor mustBe GrantLength.ONE_MONTH.period
  }

  "create application with grantLength 1 day and refreshTokensAvailableFor 1 month. refreshTokensAvailableFor is read back as 1 month " in new Setup {
    val rawJson: JsObject = applicationToMongoJson(applicationData.copy(refreshTokensAvailableFor = GrantLength.ONE_MONTH.period), None, Some(1), true)
    saveApplicationAsMongoJson(rawJson)
    val result            = await(applicationRepository.fetch(applicationId))

    result match {
      case Some(application) => {
        application.id mustBe applicationId
        application.allowAutoDelete mustBe true
        application.refreshTokensAvailableFor mustBe GrantLength.ONE_MONTH.period
      }
      case None              => fail()
    }

    val applicationSearch = new ApplicationSearch(filters = List(AutoDeleteAllowed))
    val appSearchResult   = await(applicationRepository.searchApplications("testing")(applicationSearch))

    appSearchResult.applications.size mustBe 1
    appSearchResult.applications.head.id mustBe applicationId
    appSearchResult.applications.head.allowAutoDelete mustBe true
    appSearchResult.applications.head.refreshTokensAvailableFor mustBe GrantLength.ONE_MONTH.period
  }

  "successfully remove old grantLength attribute when grantLength 1 day and refreshTokensAvailableFor 1 month" in new Setup {
    val rawJson: JsObject = applicationToMongoJson(applicationData.copy(refreshTokensAvailableFor = GrantLength.ONE_MONTH.period), None, Some(1), true)
    saveApplicationAsMongoJson(rawJson)

    val rawAppWithOldGrantLength = fetchApplicationRawJson(applicationId)
    validateGrantLength(rawAppWithOldGrantLength, true)

    await(applicationRepository.removeOldGrantLength(applicationId))

    val rawAppWithOldGrantLengthRemoved = fetchApplicationRawJson(applicationId)
    validateGrantLength(rawAppWithOldGrantLengthRemoved, false)
  }

  "do nothing when removeOldGrantLength is called if old grantLength attribute does not exist" in new Setup {
    val rawJson: JsObject = applicationToMongoJson(applicationData.copy(refreshTokensAvailableFor = GrantLength.ONE_MONTH.period), None, None, true)
    saveApplicationAsMongoJson(rawJson)

    val rawAppWithoutOldGrantLength = fetchApplicationRawJson(applicationId)
    validateGrantLength(rawAppWithoutOldGrantLength, false)

    await(applicationRepository.removeOldGrantLength(applicationId))

    val rawAppStillWithoutOldGrantLength = fetchApplicationRawJson(applicationId)
    validateGrantLength(rawAppStillWithoutOldGrantLength, false)
  }

}
