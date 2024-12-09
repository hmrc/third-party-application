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

import java.time.Clock
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationStateFixtures, DeleteRestriction}
import uk.gov.hmrc.thirdpartyapplication.component.stubs.ApiPlatformEventsStub
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.connector.DisplayEvent
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util._
import play.api.libs.json.Json

class SetDeleteRestrictionJobISpec
    extends ServerBaseISpec
    with WiremockSugar
    with MongoSupport
    with CleanMongoCollectionSupport
    with ApplicationStateFixtures
    with StoredApplicationFixtures
    with CollaboratorTestData
    with FixedClock {

  protected override def appBuilder: GuiceApplicationBuilder = {
    new GuiceApplicationBuilder()
      .configure(
        "metrics.jvm"                                    -> false,
        "microservice.services.api-platform-events.host" -> wireMockHost,
        "microservice.services.api-platform-events.port" -> wireMockPort,
        "mongodb.uri"                                    -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private val applicationRepository =
    app.injector.instanceOf[ApplicationRepository]

  private val underTest =
    app.injector.instanceOf[SetDeleteRestrictionJob]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(applicationRepository.ensureIndexes())
  }

  trait Setup {}

  "SetDeleteRestrictionJob" should {
    "update deleteRestriction fields in database" in new Setup {

      val appId1 = ApplicationId.random
      val appId2 = ApplicationId.random
      val appId3 = ApplicationId.random

      val bulkInsert = List(
        testApplicationData(appId1, true),
        testApplicationData(appId2, false),
        testApplicationData(appId3, false)
      )

      await(Future.sequence(bulkInsert.map(i => applicationRepository.save(i))))

      val reason = "reason"
      val actor  = Actors.GatekeeperUser("bob")
      val event  = DisplayEvent(appId2, instant, actor, "eventTagDescription", "Application auto delete blocked", List(reason))

      // ApiPlatformEventsStub.willReceiveQuery(appId2, "APP_LIFECYCLE", None)
      // ApiPlatformEventsStub.willReceiveQuery(appId3, "APP_LIFECYCLE", Some(List(event)))

      await(underTest.runJob)

      val actualApp1: StoredApplication = await(applicationRepository.fetch(appId1)).get
      actualApp1.deleteRestriction mustBe DeleteRestriction.NoRestriction
      val actualApp2: StoredApplication = await(applicationRepository.fetch(appId2)).get
      actualApp2.deleteRestriction mustBe DeleteRestriction.DoNotDelete("Set by process - Reason not found", Actors.ScheduledJob("SetDeleteRestrictionJob"), instant)
      val actualApp3: StoredApplication = await(applicationRepository.fetch(appId3)).get
      actualApp3.deleteRestriction mustBe DeleteRestriction.DoNotDelete(reason, actor, instant)
    }
  }

  def testApplicationData(
      id: ApplicationId,
      autoDelete: Boolean
    ): StoredApplication =
    storedApp
      .withId(id)
      .copy(
        tokens = ApplicationTokens(StoredToken(ClientId.random, "ccc")),
        allowAutoDelete = autoDelete
      )

  def stubQuery(applicationId: ApplicationId, eventTag: String, response: Option[List[DisplayEvent]]) = {
    stubFor(
      get(urlEqualTo(s"/application-event/$applicationId?eventTag=$eventTag"))
        .willReturn(
          aResponse()
            .withStatus(OK)
            .withBody(Json.toJson(response).toString())
        )
    )

  }
}
