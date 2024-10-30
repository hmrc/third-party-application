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

import java.time.{Clock, Duration, Instant}

import org.scalatest.BeforeAndAfterEach

import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.models.{Blocked, StandardAccess => _, _}
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationRepositorySearchISpec
    extends ServerBaseISpec
    with CleanMongoCollectionSupport
    with SubmissionsTestData
    with JavaDateTimeTestUtils
    with BeforeAndAfterEach
    with MetricsHelper
    with FixedClock
    with ApplicationRepositoryTestData {

  val adminName = "Admin Example"

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

  private val subscriptionRepository =
    app.injector.instanceOf[SubscriptionRepository]

  private val notificationRepository =
    app.injector.instanceOf[NotificationRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(subscriptionRepository.collection.drop().toFuture())
    await(notificationRepository.collection.drop().toFuture())

    await(applicationRepository.ensureIndexes())
    await(subscriptionRepository.ensureIndexes())
    await(notificationRepository.ensureIndexes())
  }

  private def generateClientId = ClientId.random

  "Search" should {
    def applicationWithLastAccessDate(
        applicationId: ApplicationId,
        lastAccessDate: Instant
      ): StoredApplication =
      anApplicationDataForTest(
        id = applicationId,
        prodClientId = generateClientId
      )
        .copy(lastAccess = Some(lastAccessDate))

    "correctly include the skip and limit clauses" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      val applicationSearch = new ApplicationSearch(
        pageNumber = 2,
        pageSize = 1,
        filters = List.empty
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 3
      result.matching.size mustBe 1
      result.matching.head.total mustBe 3
      result.applications.size mustBe 1                  // as a result of pageSize = 1
      result.applications.head.id mustBe application2.id // as a result of pageNumber = 2
    }

    "return application blocked from deletion" in {
      val applicationAllowedToBeDeleted = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      val applicationBlockedFromDeletion = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      ).copy(allowAutoDelete = false)

      await(applicationRepository.save(applicationAllowedToBeDeleted))
      await(applicationRepository.save(applicationBlockedFromDeletion))

      val applicationSearch = new ApplicationSearch(filters = List(AutoDeleteBlocked))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationBlockedFromDeletion.id
      result.applications.head.allowAutoDelete mustBe false
    }

    "return application allowed to be deleted" in {
      val applicationAllowedToBeDeleted = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      val applicationBlockedFromDeletion = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      ).copy(allowAutoDelete = false)

      await(applicationRepository.save(applicationAllowedToBeDeleted))
      await(applicationRepository.save(applicationBlockedFromDeletion))

      val applicationSearch = new ApplicationSearch(filters = List(AutoDeleteAllowed))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationAllowedToBeDeleted.id
      result.applications.head.allowAutoDelete mustBe true
    }

    "return applications based on application state filter Active" in {
      val applicationInTest       = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val applicationInProduction =
        createAppWithStatusUpdatedOn(State.PRODUCTION)
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationInProduction))

      val applicationSearch = new ApplicationSearch(filters = List(Active))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationInProduction.id
    }

    "return applications based on application state filter WasDeleted" in {
      val applicationInTest  = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val applicationDeleted =
        createAppWithStatusUpdatedOn(State.DELETED)
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationDeleted))

      val applicationSearch = new ApplicationSearch(filters = List(WasDeleted), includeDeleted = true)

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationDeleted.id
    }

    "return applications based on application state filter ExcludingDeleted" in {
      val applicationInTest  = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val applicationDeleted =
        createAppWithStatusUpdatedOn(State.DELETED)
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationDeleted))

      val applicationSearch = new ApplicationSearch(filters = List(ExcludingDeleted), includeDeleted = true)

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationInTest.id
    }

    "return applications based on access type filter" in {
      val standardApplication = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val ropcApplication     = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .withAccess(Access.Ropc())
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ROPCAccess))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe ropcApplication.id
      result.applications.head.blocked mustBe false
    }

    "return applications based on blocked filter" in {
      val standardApplication = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val blockedApplication  = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      ).copy(blocked = true)

      val deletedAndBlockedApplication = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .withState(appStateDeleted)
        .copy(blocked = true)

      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(blockedApplication))
      await(applicationRepository.save(deletedAndBlockedApplication))

      val applicationSearch = new ApplicationSearch(filters = List(Blocked))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 3
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe blockedApplication.id
      result.applications.head.blocked mustBe true
    }

    "return applications with no API subscriptions" in {
      val applicationWithSubscriptions    = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val applicationWithoutSubscriptions = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(applicationWithSubscriptions))
      await(applicationRepository.save(applicationWithoutSubscriptions))
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "context",
              "version-1",
              applicationWithSubscriptions.id
            )
          )
          .toFuture()
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(NoAPISubscriptions))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationWithoutSubscriptions.id
    }

    "return applications with any API subscriptions" in {
      val applicationWithSubscriptions    = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val applicationWithoutSubscriptions = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(applicationWithSubscriptions))
      await(applicationRepository.save(applicationWithoutSubscriptions))
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "context",
              "version-1",
              applicationWithSubscriptions.id
            )
          )
          .toFuture()
      )

      val applicationSearch =
        ApplicationSearch(filters = List(OneOrMoreAPISubscriptions))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))
      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationWithSubscriptions.id
    }

    "return applications with search text matching application id" in {
      val applicationId   = ApplicationId.random
      val applicationName = "Test Application 1"

      val application            = aNamedApplicationData(
        applicationId,
        prodClientId = generateClientId
      )
      .withName(ApplicationName(applicationName))

      val randomOtherApplication = anApplicationDataForTest(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ApplicationTextSearch),
        textToSearch = Some(applicationId.value.toString)
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationId
    }

    "return applications with search text matching application name" in {
      val applicationId   = ApplicationId.random
      val applicationName = "Test Application 2"

      val application              = aNamedApplicationData(
        applicationId,
        prodClientId = generateClientId
      )
      .withName(ApplicationName(applicationName))

      val randomOtherApplication   = anApplicationDataForTest(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      val randomDeletedApplication = aNamedApplicationData(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      .withName(ApplicationName(applicationName))

      await(applicationRepository.save(randomDeletedApplication))
      await(applicationRepository.delete(randomDeletedApplication.id, instant))
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ApplicationTextSearch),
        textToSearch = Some(applicationName)
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 3
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationId
    }

    "return applications with search text matching application name including deleted" in {
      val applicationId   = ApplicationId.random
      val applicationName = "Test Application 2"

      val application              = aNamedApplicationData(
        applicationId,
        prodClientId = generateClientId
      )
      .withName(ApplicationName(applicationName))

      val randomOtherApplication   = anApplicationDataForTest(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      val randomDeletedApplication = aNamedApplicationData(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      .withName(ApplicationName(applicationName))
      
      await(applicationRepository.save(randomDeletedApplication))
      await(applicationRepository.delete(randomDeletedApplication.id, instant))
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ApplicationTextSearch),
        textToSearch = Some(applicationName),
        includeDeleted = true
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 3
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.id mustBe randomDeletedApplication.id
      result.applications.tail.head.id mustBe applicationId
    }

    "return applications with search text matching client id" in {
      val applicationId   = ApplicationId.random
      val clientId        = generateClientId
      val applicationName = "Test Application"

      val application            = aNamedApplicationData(
        applicationId,
        prodClientId = clientId
      )
      .withName(ApplicationName(applicationName))

      val randomOtherApplication = anApplicationDataForTest(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ApplicationTextSearch),
        textToSearch = Some(clientId.value)
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.tokens.production.clientId mustBe clientId
    }

    "return applications with matching search text and other filters" in {
      val applicationName = "Test Application"

      // Applications with the same name, but different access levels
      val standardApplication =
        aNamedApplicationData(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        .withName(ApplicationName(applicationName))

      val ropcApplication     =
        aNamedApplicationData(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        .withName(ApplicationName(applicationName))
        .withAccess(Access.Ropc())
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ROPCAccess),
        textToSearch = Some(applicationName)
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      // Only ROPC application should be returned
      result.applications.size mustBe 1
      result.applications.head.id mustBe ropcApplication.id
    }

    "return applications matching search text in a case-insensitive manner" in {

      val application            = aNamedApplicationData(
        applicationId,
        prodClientId = generateClientId
      )
      .withName(ApplicationName("TEST APPLICATION"))

      val randomOtherApplication = anApplicationDataForTest(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ApplicationTextSearch),
        textToSearch = Some("application")
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationId
    }

    "return applications subscribing to a specific API" in {
      val expectedAPIContext = "match-this-api"
      val otherAPIContext    = "do-not-match-this-api"

      val expectedApplication = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val otherApplication    = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(expectedApplication))
      await(applicationRepository.save(otherApplication))
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              expectedAPIContext,
              "version-1",
              expectedApplication.id
            )
          )
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(otherAPIContext, "version-1", otherApplication.id)
          )
          .toFuture()
      )

      val applicationSearch = new ApplicationSearch(
        filters = List(SpecificAPISubscription),
        apiContext = Some(expectedAPIContext.asContext),
        apiVersion = None
      )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe expectedApplication.id
    }

    "return applications subscribing to a specific version of an API" in {
      val apiContext         = "match-this-api"
      val expectedAPIVersion = "version-1"
      val otherAPIVersion    = "version-2"

      val expectedApplication = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val otherApplication    = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(expectedApplication))
      await(applicationRepository.save(otherApplication))
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              apiContext,
              expectedAPIVersion,
              expectedApplication.id
            )
          )
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(apiContext, otherAPIVersion, otherApplication.id)
          )
          .toFuture()
      )

      val applicationSearch =
        new ApplicationSearch(
          filters = List(SpecificAPISubscription),
          apiContext = Some(apiContext.asContext),
          apiVersion = Some(expectedAPIVersion.asVersion)
        )

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe expectedApplication.id
    }

    "return applications last used before a certain date" in {
      val oldApplicationId = ApplicationId.random
      val cutoffDate       = instant.minus(Duration.ofDays(365))

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            oldApplicationId,
            instant.minus(Duration.ofDays(18 * 30))
          )
        )
      )
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            instant
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe oldApplicationId
    }

    "include applications with no lastAccess date where they were created before cutoff date" in {
      val oldApplicationId = ApplicationId.random
      val oldApplication   = anApplicationDataForTest(oldApplicationId).copy(
        createdOn = instant.minus(Duration.ofDays(18 * 30)),
        lastAccess = None
      )
      await(applicationRepository.save(oldApplication))
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            instant
          )
        )
      )

      val cutoffDate        = instant.minus(Duration.ofDays(12 * 30))
      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe oldApplicationId
    }

    "return applications that are equal to the specified cutoff date when searching for older applications" in {
      val oldApplicationId = ApplicationId.random
      val cutoffDate       = instant.minus(Duration.ofDays(12 * 30))

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(oldApplicationId, cutoffDate)
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 1
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe oldApplicationId
    }

    "return no results if no applications are last used before the cutoff date" in {
      val cutoffDate = instant.minus(Duration.ofDays(12 * 30))
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            instant
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.applications.size mustBe 0
    }

    "return applications last used after a certain date" in {
      val newerApplicationId = ApplicationId.random
      val cutoffDate         = instant.minus(Duration.ofDays(12 * 30))

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            newerApplicationId,
            instant
          )
        )
      )
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            instant.minus(Duration.ofDays(18 * 30))
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe newerApplicationId
    }

    "include applications with no lastAccess date where they were created after cutoff date" in {
      val newerApplicationId = ApplicationId.random
      val newerApplication   =
        anApplicationDataForTest(newerApplicationId).copy(lastAccess = None)
      val cutoffDate         = instant.minus(Duration.ofDays(12 * 30))

      await(applicationRepository.save(newerApplication))
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            instant.minus(Duration.ofDays(18 * 30))
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe newerApplicationId
    }

    "return applications that are equal to the specified cutoff date when searching for newer applications" in {

      val cutoffDate = instant.minus(Duration.ofDays(365))

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(applicationId, cutoffDate)
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 1
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationId
    }

    "return no results if no applications are last used after the cutoff date" in {
      val cutoffDate = instant
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            instant.minus(Duration.ofDays(6 * 30))
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.applications.size mustBe 0
    }

    "return applications sorted by name ascending" in {
      val firstName     = "AAA first"
      val secondName    = "ZZZ third"
      val lowerCaseName = "aaa second"

      val firstApplication     =
        aNamedApplicationData(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        .withName(ApplicationName(firstName))

      val secondApplication    =
        aNamedApplicationData(
          id = ApplicationId.random,
          prodClientId = generateClientId
          )
          .withName(ApplicationName(secondName))

      val lowerCaseApplication =
        aNamedApplicationData(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        .withName(ApplicationName(lowerCaseName))

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(lowerCaseApplication))

      val applicationSearch = new ApplicationSearch(sort = NameAscending)
      val result            =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 3
      result.matching.size mustBe 1
      result.matching.head.total mustBe 3
      result.applications.size mustBe 3
      result.applications.head.name.value mustBe firstName
      result.applications.tail.head.name.value mustBe lowerCaseName
      result.applications.last.name.value mustBe secondName
    }

    "return applications sorted by name descending" in {
      val firstName         = "AAA first"
      val secondName        = "ZZZ second"
      val firstApplication  =
        aNamedApplicationData(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        .withName(ApplicationName(firstName))
      val secondApplication =
        aNamedApplicationData(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        .withName(ApplicationName(secondName))

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val applicationSearch = new ApplicationSearch(sort = NameDescending)
      val result            =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.name.value mustBe secondName
      result.applications.last.name.value mustBe firstName
    }

    "return applications sorted by submitted ascending" in {
      val firstCreatedOn    = instant.minus(Duration.ofDays(2))
      val secondCreatedOn   = instant.minus(Duration.ofDays(1))
      val firstApplication  =
        anApplicationDataForTest(
          id = ApplicationId.random,
          prodClientId = generateClientId
        ).copy(createdOn = firstCreatedOn)
      val secondApplication =
        anApplicationDataForTest(
          id = ApplicationId.random,
          prodClientId = generateClientId
        ).copy(createdOn = secondCreatedOn)

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch = new ApplicationSearch(sort = SubmittedAscending)
      val result            =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.createdOn mustBe firstCreatedOn
      result.applications.last.createdOn mustBe secondCreatedOn
    }

    "return applications sorted by submitted descending" in {
      val firstCreatedOn    = instant.minus(Duration.ofDays(2))
      val secondCreatedOn   = instant.minus(Duration.ofDays(1))
      val firstApplication  =
        anApplicationDataForTest(
          id = ApplicationId.random,
          prodClientId = generateClientId
        ).copy(createdOn = firstCreatedOn)
      val secondApplication =
        anApplicationDataForTest(
          id = ApplicationId.random,
          prodClientId = generateClientId
        ).copy(createdOn = secondCreatedOn)

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val applicationSearch = new ApplicationSearch(sort = SubmittedDescending)
      val result            =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.createdOn mustBe secondCreatedOn
      result.applications.last.createdOn mustBe firstCreatedOn
    }

    "return applications sorted by lastAccess ascending" in {
      val mostRecentlyAccessedDate = instant.minus(Duration.ofDays(1))
      val oldestLastAccessDate     = instant.minus(Duration.ofDays(2))
      val firstApplication         = applicationWithLastAccessDate(
        ApplicationId.random,
        mostRecentlyAccessedDate
      )
      val secondApplication        = applicationWithLastAccessDate(
        ApplicationId.random,
        oldestLastAccessDate
      )

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch = new ApplicationSearch(sort = LastUseDateAscending)
      val result            =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.lastAccess mustBe Some(oldestLastAccessDate)
      result.applications.last.lastAccess mustBe Some(mostRecentlyAccessedDate)
    }

    "return applications sorted by lastAccess descending" in {
      val mostRecentlyAccessedDate = instant.minus(Duration.ofDays(1))
      val oldestLastAccessDate     = instant.minus(Duration.ofDays(2))
      val firstApplication         = applicationWithLastAccessDate(
        ApplicationId.random,
        mostRecentlyAccessedDate
      )
      val secondApplication        = applicationWithLastAccessDate(
        ApplicationId.random,
        oldestLastAccessDate
      )

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch =
        new ApplicationSearch(sort = LastUseDateDescending)
      val result            =
        await(applicationRepository.searchApplications("testing")(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.lastAccess mustBe Some(mostRecentlyAccessedDate)
      result.applications.last.lastAccess mustBe Some(oldestLastAccessDate)
    }
  }

}
