/*
 * Copyright 2025 HM Revenue & Customs
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

import java.time.Clock

import org.scalatest.BeforeAndAfterEach

import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.GrantLength
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationQueriesISpec
    extends ServerBaseISpec
    with CleanMongoCollectionSupport
    with SubmissionsTestData
    with StoredApplicationFixtures
    with JavaDateTimeTestUtils
    with BeforeAndAfterEach
    with MetricsHelper
    with FixedClock
    with ApplicationRepositoryTestData {

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private lazy val applicationRepository =
    app.injector.instanceOf[ApplicationRepository]

  private lazy val subscriptionRepository =
    app.injector.instanceOf[SubscriptionRepository]

  private lazy val stateHistoryRepository =
    app.injector.instanceOf[StateHistoryRepository]

  private lazy val notificationRepository =
    app.injector.instanceOf[NotificationRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(subscriptionRepository.collection.drop().toFuture())
    await(notificationRepository.collection.drop().toFuture())
    await(stateHistoryRepository.collection.drop().toFuture())

    await(stateHistoryRepository.ensureIndexes())
    await(notificationRepository.ensureIndexes())
    await(subscriptionRepository.ensureIndexes())
    await(applicationRepository.ensureIndexes())
  }

  "applicationByClientId" should {
    trait Setup {
      val grantLength1 = GrantLength.ONE_MONTH.period
      val grantLength2 = GrantLength.ONE_YEAR.period

      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        clientIdOne
      )
        .withState(appStateProduction)
        .copy(refreshTokensAvailableFor = grantLength1)

      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        clientIdTwo
      )
        .withState(appStateProduction)
        .copy(refreshTokensAvailableFor = grantLength2)

      val application3 = anApplicationDataForTest(
        ApplicationId.random,
        clientIdThree
      )
        .withState(appStateDeleted)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
    }

    "retrieve the application for a given client id when it has a matching client id" in new Setup {
      val retrieved = await(applicationRepository.fetchSingleApplication(ApplicationQueries.applicationByClientId(clientIdTwo)))

      retrieved.value shouldBe application2
    }

    "retrieve the grant length for an application for a given client id when it has a matching client id" in new Setup {
      val retrieved1 = await(applicationRepository.fetchSingleApplication(ApplicationQueries.applicationByClientId(clientIdOne)))
      val retrieved2 = await(applicationRepository.fetchSingleApplication(ApplicationQueries.applicationByClientId(clientIdTwo)))

      retrieved1.value.refreshTokensAvailableFor shouldBe grantLength1
      retrieved2.value.refreshTokensAvailableFor shouldBe grantLength2
    }

    "do not retrieve the application for a given client id when it has a matching client id but is deleted" in new Setup {
      val retrieved = await(applicationRepository.fetchSingleApplication(ApplicationQueries.applicationByClientId(application3.tokens.production.clientId)))

      retrieved shouldBe None
    }
  }

  "applicationByServerToken" should {

    "retrieve the application when it is matched for access token" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        clientIdOne
      )
        .withState(appStateProduction)

      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        clientIdTwo
      )
        .withState(appStateProduction)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchSingleApplication(ApplicationQueries.applicationByServerToken(application2.tokens.production.accessToken)))

      retrieved shouldBe Some(application2)
    }

    "do not retrieve the application when it is matched for access token but is deleted" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa")
      )
        .withState(appStateDeleted)

      await(applicationRepository.save(application1))

      val retrieved = await(applicationRepository.fetchSingleApplication(ApplicationQueries.applicationByServerToken(application1.tokens.production.accessToken)))

      retrieved shouldBe None
    }
  }

  "standardNonTestingApps" should {
    trait Setup {

      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
        .withState(appStatePendingRequesterVerification)

      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
        .withState(appStateProduction)

      val application4 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
        .withState(appStatePendingRequesterVerification)

      val application5 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
        .withState(appStateDeleted)

      def test = applicationRepository.fetchApplications(ApplicationQueries.standardNonTestingApps)
    }

    "retrieve all the standard applications not in TESTING (or DELETED) state" in new Setup {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      val retrieved = await(test)

      retrieved.length shouldBe 3
      retrieved.toSet shouldBe Set(application2, application3, application4)
    }

    "return empty list when no apps are found" in new Setup {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application5))

      await(test) shouldBe Nil
    }

    "not return Access.Privileged applications" in new Setup {
      val application = anApplicationDataForTest(ApplicationId.random)
        .withState(appStateProduction)
        .withAccess(Access.Privileged())

      await(applicationRepository.save(application))

      await(test) shouldBe Nil
    }

    "not return ROPC applications" in new Setup {
      val application = anApplicationDataForTest(ApplicationId.random)
        .withState(appStateProduction)
        .withAccess(Access.Ropc())

      await(applicationRepository.save(application))
      await(test) shouldBe Nil
    }

    "return empty list when all apps in TESTING state" in new Setup {
      val application = anApplicationDataForTest(ApplicationId.random)

      await(applicationRepository.save(application))
      await(test) shouldBe Nil
    }

    "return empty list when all apps in DELETED state" in new Setup {
      val application = anApplicationDataForTest(ApplicationId.random).withState(appStateDeleted)

      await(applicationRepository.save(application))
      await(test) shouldBe Nil
    }
  }

  "applicationsByName" should {

    "retrieve the application with the matching name" in {
      val applicationName           = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByName(applicationName)))

      retrieved shouldBe List(application)
    }

    "dont retrieve the application if it's a non-matching name" in {
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByName("non-matching-name")))

      retrieved shouldBe List.empty
    }

    "dont retrieve the application with the matching name if its deleted" in {
      val applicationName           = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .withState(appStateDeleted)
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByName(applicationName)))

      retrieved shouldBe List.empty
    }
  }

  "applicationsByUserId" should {
    "return two applications when all have the same userId" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random

      val collaborator     = "user@example.com".admin(userId)
      val testApplication1 = anApplicationDataForTest(applicationId1).withCollaborators(collaborator)
      val testApplication2 = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb")).withCollaborators(collaborator)
      val testApplication3 = anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc")).withCollaborators(collaborator).withState(appStateDeleted)

      await(applicationRepository.save(testApplication1))
      await(applicationRepository.save(testApplication2))
      await(applicationRepository.save(testApplication3))

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByUserId(userId, false)))

      result.size shouldBe 2
      result.map(
        _.collaborators.map(collaborator => collaborator.userId shouldBe userId)
      )
    }

    "return three applications when all have the same userId and one is deleted" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random

      val collaborator     = "user@example.com".admin(userId)
      val testApplication1 = anApplicationDataForTest(applicationId1).withCollaborators(collaborator)
      val testApplication2 = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb")).withCollaborators(collaborator)
      val testApplication3 = anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc")).withCollaborators(collaborator).withState(appStateDeleted)

      await(applicationRepository.save(testApplication1))
      await(applicationRepository.save(testApplication2))
      await(applicationRepository.save(testApplication3))

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByUserId(userId, true)))

      result.size shouldBe 3
      result.map(
        _.collaborators.map(collaborator => collaborator.userId shouldBe userId)
      )
    }
  }

  "applicationsByUserIdAndEnvironment" should {
    "return one application when 3 apps have the same userId but only one is in Production and not deleted" in {
      val collaborator       = "user@example.com".admin(userIdOne)
      val applicationId1     = ApplicationId.random
      val applicationId2     = ApplicationId.random
      val applicationId3     = ApplicationId.random
      val prodApplication1   = anApplicationDataForTest(applicationId1).withCollaborators(collaborator)
      val prodApplication2   = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb")).withCollaborators(collaborator).withState(appStateDeleted)
      val sandboxApplication = anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc")).withCollaborators(collaborator).inSandbox()

      await(applicationRepository.save(prodApplication1))
      await(applicationRepository.save(prodApplication2))
      await(applicationRepository.save(sandboxApplication))

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByUserIdAndEnvironment(userIdOne, Environment.PRODUCTION)))

      result.size shouldBe 1
      result.head.environment shouldBe Environment.PRODUCTION
      result.map(
        _.collaborators.map(collaborator => collaborator.userId shouldBe userIdOne)
      )
    }
  }

  "applicationsByNoSubscriptions" should {
    "fetch only those applications with no subscriptions" in {

      val application1     = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application2     = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val subscriptionData =
        aSubscriptionData("context", "version", application1.id)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(subscriptionRepository.collection.insertOne(subscriptionData).toFuture())

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByNoSubscriptions))

      result shouldBe List(application2)
    }
  }

  "applicationsByApiContext" should {

    "fetch only those applications when the context matches" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-1", application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-2", application2.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("other", "version-2", application3.id))
          .toFuture()
      )

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByApiContext("context".asContext)))

      result shouldBe List(application1, application2)
    }
  }

  "applicationsByApiIdentifier" should {

    "fetch only those applications when the context and version matches" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-1", application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-2", application2.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "other",
              "version-2",
              application2.id,
              application3.id
            )
          )
          .toFuture()
      )

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByApiIdentifier("context".asIdentifier("version-2"))))

      result shouldBe List(application2)
    }

    "fetch multiple applications with the same matching context and versions" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-1", application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "context",
              "version-2",
              application2.id,
              application3.id
            )
          )
          .toFuture()
      )

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByApiIdentifier("context".asIdentifier("version-2"))))

      result shouldBe List(application2, application3)
    }

    "fetch no applications when the context and version do not match" in {
      val application1                            = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val application2                            = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = ClientId.random
      )
      val nonExistentApiIdentifier: ApiIdentifier =
        "other".asIdentifier("version-1")

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData("context-1", "version-1", application1.id)
          )
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData("context-2", "version-2", application2.id)
          )
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "other",
              "version-2",
              application1.id,
              application2.id
            )
          )
          .toFuture()
      )

      val result = await(applicationRepository.fetchApplications(ApplicationQueries.applicationsByApiIdentifier(nonExistentApiIdentifier)))

      result shouldBe List.empty
    }
  }

}
