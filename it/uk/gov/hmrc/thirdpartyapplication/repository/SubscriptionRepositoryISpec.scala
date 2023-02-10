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

package uk.gov.hmrc.thirdpartyapplication.repository

import org.scalatest.concurrent.Eventually
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.metrics.SubscriptionCountByApi
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.{FixedClock, JavaDateTimeTestUtils, MetricsHelper}
import uk.gov.hmrc.utils.ServerBaseISpec
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborators.Roles

import java.time.Clock
import scala.util.Random.nextString
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

class SubscriptionRepositoryISpec
    extends ServerBaseISpec
    with JavaDateTimeTestUtils
    with BeforeAndAfterEach
    with MetricsHelper
    with CleanMongoCollectionSupport
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with Eventually
    with TableDrivenPropertyChecks
    with ApiIdentifierSyntax
    with FixedClock {

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private val subscriptionRepository: SubscriptionRepository = app.injector.instanceOf[SubscriptionRepository]
  private val applicationRepository: ApplicationRepository   = app.injector.instanceOf[ApplicationRepository]

  protected override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(subscriptionRepository.collection.drop().toFuture())

    await(applicationRepository.ensureIndexes)
    await(subscriptionRepository.ensureIndexes)
  }

  "add" should {

    "create an entry" in {
      val applicationId = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")

      val result = await(subscriptionRepository.add(applicationId, apiIdentifier))

      result mustBe HasSucceeded
    }

    "create multiple subscriptions" in {
      val application1  = ApplicationId.random
      val application2  = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))

      val result = await(subscriptionRepository.add(application2, apiIdentifier))

      result mustBe HasSucceeded
    }
  }

  "remove" should {
    "delete the subscription" in {
      val application1  = ApplicationId.random
      val application2  = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))
      await(subscriptionRepository.add(application2, apiIdentifier))

      val result = await(subscriptionRepository.remove(application1, apiIdentifier))

      result mustBe HasSucceeded
      await(subscriptionRepository.isSubscribed(application1, apiIdentifier)) mustBe false
      await(subscriptionRepository.isSubscribed(application2, apiIdentifier)) mustBe true
    }

    "not fail when deleting a non-existing subscription" in {
      val application1  = ApplicationId.random
      val application2  = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))

      val result = await(subscriptionRepository.remove(application2, apiIdentifier))

      result mustBe HasSucceeded
      await(subscriptionRepository.isSubscribed(application1, apiIdentifier)) mustBe true
    }
  }

  "find all" should {
    "retrieve all versions subscriptions" in {
      val application1   = ApplicationId.random
      val application2   = ApplicationId.random
      val apiIdentifierA = "some-context-a".asIdentifier("1.0.0")
      val apiIdentifierB = "some-context-b".asIdentifier("1.0.2")
      await(subscriptionRepository.add(application1, apiIdentifierA))
      await(subscriptionRepository.add(application2, apiIdentifierA))
      await(subscriptionRepository.add(application2, apiIdentifierB))
      val retrieved      = await(subscriptionRepository.findAll)
      retrieved mustBe List(
        subscriptionData("some-context-a".asContext, "1.0.0".asVersion, application1, application2),
        subscriptionData("some-context-b".asContext, "1.0.2".asVersion, application2)
      )
    }
  }

  "isSubscribed" should {

    "return true when the application is subscribed" in {
      val applicationId = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(applicationId, apiIdentifier))

      val isSubscribed = await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier))

      isSubscribed mustBe true
    }

    "return false when the application is not subscribed" in {
      val applicationId = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")

      val isSubscribed = await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier))

      isSubscribed mustBe false
    }
  }

  "getSubscriptions" should {
    val application1 = ApplicationId.random
    val application2 = ApplicationId.random
    val api1         = "some-context".asIdentifier("1.0")
    val api2         = "some-context".asIdentifier("2.0")
    val api3         = "some-context".asIdentifier("3.0")

    "return the subscribed APIs" in {
      await(subscriptionRepository.add(application1, api1))
      await(subscriptionRepository.add(application1, api2))
      await(subscriptionRepository.add(application2, api3))

      val result = await(subscriptionRepository.getSubscriptions(application1))

      result mustBe List(api1, api2)
    }

    "return empty when the application is not subscribed to any API" in {
      val result = await(subscriptionRepository.getSubscriptions(application1))

      result mustBe List.empty
    }
  }

  "getSubscribers" should {
    val application1 = ApplicationId.random
    val application2 = ApplicationId.random
    val api1         = "some-context".asIdentifier("1.0")
    val api2         = "some-context".asIdentifier("2.0")
    val api3         = "some-context".asIdentifier("3.0")

    def saveSubscriptions(): HasSucceeded = {
      await(subscriptionRepository.add(application1, api1))
      await(subscriptionRepository.add(application1, api2))
      await(subscriptionRepository.add(application2, api2))
      await(subscriptionRepository.add(application2, api3))
    }

    "return an empty set when the API doesn't have any subscribers" in {
      saveSubscriptions()

      val applications: Set[ApplicationId] = await(subscriptionRepository.getSubscribers("some-context".asIdentifier("4.0")))

      applications must have size 0
    }

    "return the IDs of the applications subscribed to the given API" in {
      saveSubscriptions()
      val scenarios = Table(
        ("apiIdentifier", "expectedApplications"),
        ("some-context".asIdentifier("1.0"), Seq(application1)),
        ("some-context".asIdentifier("2.0"), Seq(application1, application2)),
        ("some-context".asIdentifier("3.0"), Seq(application2))
      )

      forAll(scenarios) { (apiIdentifier, expectedApplications) =>
        val applications: Set[ApplicationId] = await(subscriptionRepository.getSubscribers(apiIdentifier))
        applications must contain only (expectedApplications: _*)
      }
    }
  }

  "Get API Version Collaborators" should {

    "return email addresses" in {
      val app1 = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("match1@example.com", "match2@example.com"))
      await(applicationRepository.save(app1))

      val app2 = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("match3@example.com"))
      await(applicationRepository.save(app2))

      val doNotMatchApp = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("donotmatch@example.com"))
      await(applicationRepository.save(doNotMatchApp))

      val api1 = "some-context-api1".asIdentifier("1.0")
      await(subscriptionRepository.add(app1.id, api1))
      await(subscriptionRepository.add(app2.id, api1))

      val doNotMatchApi = "some-context-donotmatchapi".asIdentifier("1.0")
      await(subscriptionRepository.add(doNotMatchApp.id, doNotMatchApi))

      val result = await(subscriptionRepository.searchCollaborators(api1.context, api1.version, None))

      val expectedEmails = app1.collaborators.map(c => c.emailAddress) ++ app2.collaborators.map(c => c.emailAddress)
      result.toSet mustBe expectedEmails
    }

    "filter by collaborators and api version" in {
      val emailToMatch = "match@example.com"

      val partialEmailToMatch = "match"
      val app1                = anApplicationData(
        id = ApplicationId.random,
        clientId = generateClientId,
        user = List(emailToMatch, "donot@example.com")
      )

      await(applicationRepository.save(app1))

      val api1 = "some-context-api".asIdentifier("1.0")
      await(subscriptionRepository.add(app1.id, api1))

      val result = await(subscriptionRepository.searchCollaborators(api1.context, api1.version, Some(partialEmailToMatch)))

      result.toSet mustBe Set(emailToMatch)
    }
  }

  "ApisWithSubscriptionCount" should {
    "return APIs with a count of subscriptions" in {
      val api1        = "api-1"
      val api2        = "api-2"
      val api3        = "api-3"
      val api1Version = "api-1-version-1"
      val api2Version = "api-2-version-2"
      val api3Version = "api-3-version-3"

      val application1 = anApplicationData(id = ApplicationId.random, clientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, clientId = generateClientId)
      val application3 = anApplicationData(id = ApplicationId.random, clientId = generateClientId)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      // application 3 is intentionally not saved

      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api1, api1Version, application1.id, application2.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api2, api2Version, application1.id, application2.id, application3.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api3, api3Version, application3.id))
          .toFuture()
      )

      val expectedResult = List(
        SubscriptionCountByApi(ApiIdentifier(ApiContext(api1), ApiVersion(api1Version)), 2),
        SubscriptionCountByApi(ApiIdentifier(ApiContext(api2), ApiVersion(api2Version)), 2)
      )

      val result = await(subscriptionRepository.getSubscriptionCountByApiCheckingApplicationExists)

      result must contain theSameElementsAs expectedResult
    }
  }

  "handle ApiSubscribed event correctly" in {
    val applicationId = ApplicationId.random
    val apiIdentifier = "some-context".asIdentifier("1.0.0")

    val result = await(subscriptionRepository.add(applicationId, apiIdentifier))

    result mustBe HasSucceeded
    await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier)) mustBe true
  }

  "handle ApiUnsubscribed event correctly" in {
    val applicationId = ApplicationId.random
    val apiIdentifier = "some-context".asIdentifier("1.0.0")

    await(subscriptionRepository.add(applicationId, apiIdentifier))

    val result = await(subscriptionRepository.remove(applicationId, apiIdentifier))

    result mustBe HasSucceeded
    await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier)) mustBe false
  }

  def subscriptionData(apiContext: ApiContext, version: ApiVersion, applicationIds: ApplicationId*): SubscriptionData = {
    SubscriptionData(
      ApiIdentifier(apiContext, version),
      Set(applicationIds: _*)
    )
  }

  def aSubscriptionData(apiContext: String, version: String, applicationIds: ApplicationId*): SubscriptionData = {
    subscriptionData(ApiContext(apiContext), ApiVersion(version), applicationIds: _*)
  }

  def anApplicationData(
      id: ApplicationId,
      clientId: ClientId = ClientId("aaa"),
      state: ApplicationState = testingState(),
      access: Access = Standard(),
      user: List[String] = List("user@example.com"),
      checkInformation: Option[CheckInformation] = None
    ): ApplicationData = {

    aNamedApplicationData(id, s"myApp-${id.value}", clientId, state, access, user, checkInformation)
  }

  def aNamedApplicationData(
      id: ApplicationId,
      name: String,
      clientId: ClientId = ClientId("aaa"),
      state: ApplicationState = testingState(),
      access: Access = Standard(),
      user: List[String] = List("user@example.com"),
      checkInformation: Option[CheckInformation] = None
    ): ApplicationData = {

    val collaborators = user.map(email => Collaborator(email, Roles.ADMINISTRATOR, UserId.random)).toSet

    ApplicationData(
      id,
      name,
      name.toLowerCase,
      collaborators,
      Some("description"),
      "myapplication",
      ApplicationTokens(Token(clientId, generateAccessToken)),
      state,
      access,
      FixedClock.now,
      Some(FixedClock.now),
      checkInformation = checkInformation
    )
  }

  private def generateClientId = {
    ClientId.random
  }

  private def generateAccessToken = {
    val testAccessTokenLength = 5
    nextString(testAccessTokenLength)
  }

}
