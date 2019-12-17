/*
 * Copyright 2019 HM Revenue & Customs
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

package it.uk.gov.hmrc.thirdpartyapplication.repository

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.DateTime
import org.mockito.MockitoSugar
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random.{alphanumeric, nextString}

class ApplicationRepositorySpec
  extends UnitSpec
    with MockitoSugar
    with MongoSpecSupport
    with BeforeAndAfterEach with BeforeAndAfterAll
    with ApplicationStateUtil
    with IndexVerification
    with Eventually
    with Matchers {

  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val applicationRepository = new ApplicationRepository(reactiveMongoComponent)
  private val subscriptionRepository = new SubscriptionRepository(reactiveMongoComponent)

  private def generateClientId = {
    val lengthOfRandomClientId = 10
    alphanumeric.take(lengthOfRandomClientId).mkString
  }

  private def generateClientSecret = {
    val lengthOfRandomSecret = 5
    nextString(lengthOfRandomSecret)
  }

  private def generateAccessToken = {
    val lengthOfRandomToken = 5
    nextString(lengthOfRandomToken)
  }

  override def beforeEach() {
    Seq(applicationRepository, subscriptionRepository).foreach { db =>
      await(db.drop)
      await(db.ensureIndexes)
    }
  }

  override protected def afterAll() {
    Seq(applicationRepository, subscriptionRepository).foreach { db =>
      await(db.drop)
    }
  }

  "save" should {

    "create an application and retrieve it from database" in {

      val application = anApplicationData(UUID.randomUUID())

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get

      retrieved shouldBe application
    }

    "update an application" in {

      val application = anApplicationData(UUID.randomUUID())

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get

      retrieved shouldBe application

      val updated = retrieved.copy(name = "new name")

      await(applicationRepository.save(updated))

      val newRetrieved = await(applicationRepository.fetch(application.id)).get

      newRetrieved shouldBe updated

    }

  }

  "updateApplicationRateLimit" should {

    "set the rateLimitTier field on an Application document" in {
      val applicationId = UUID.randomUUID()
      await(
        applicationRepository.save(
          anApplicationData(applicationId, "aaa", productionState("requestorEmail@example.com")).copy(rateLimitTier = Some(RateLimitTier.BRONZE))))

      val updatedRateLimit = RateLimitTier.GOLD

      val updatedApplication = await(applicationRepository.updateApplicationRateLimit(applicationId, updatedRateLimit))

      updatedApplication.rateLimitTier shouldBe Some(updatedRateLimit)
    }

    "set the rateLimitTier field on an Application document where none previously existed" in {
      val applicationId = UUID.randomUUID()
      await(
        applicationRepository.save(
          anApplicationData(applicationId, "aaa", productionState("requestorEmail@example.com")).copy(rateLimitTier = None)))

      val updatedRateLimit = RateLimitTier.GOLD

      val updatedApplication = await(applicationRepository.updateApplicationRateLimit(applicationId, updatedRateLimit))

      updatedApplication.rateLimitTier shouldBe Some(updatedRateLimit)
    }
  }

  "updateApplicationIpWhitelist" should {
    "set the ipWhitelist field on an Application document" in {
      val applicationId = UUID.randomUUID()
      await(applicationRepository.save(anApplicationData(applicationId)))
      val updatedIpWhitelist = Set("192.168.100.0/22", "192.168.104.1/32")

      val updatedApplication = await(applicationRepository.updateApplicationIpWhitelist(applicationId, updatedIpWhitelist))

      updatedApplication.ipWhitelist shouldBe updatedIpWhitelist
    }
  }

  "recordApplicationUsage" should {
    "update the lastAccess property" in {
      val testStartTime = DateTime.now()

      val applicationId = UUID.randomUUID()

      val application =
        anApplicationData(applicationId, "aaa", productionState("requestorEmail@example.com"))
          .copy(lastAccess = Some(DateTime.now.minusDays(20))) // scalastyle:ignore magic.number

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.recordApplicationUsage(applicationId))

      retrieved.lastAccess.get.isAfter(testStartTime) shouldBe true
    }
  }

  "fetchByClientId" should {

    "retrieve the application for a given client id when it has a matching client id" in {

      val application1 = anApplicationData(UUID.randomUUID(), "aaa", productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(UUID.randomUUID(), "zzz", productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByClientId(application2.tokens.production.clientId))

      retrieved shouldBe Some(application2)

    }

  }

  "fetchByServerToken" should {

    "retrieve the application when it is matched for access token" in {

      val application1 = anApplicationData(UUID.randomUUID(), "aaa", productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(UUID.randomUUID(), "zzz", productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByServerToken(application2.tokens.production.accessToken))

      retrieved shouldBe Some(application2)
    }
  }

  "fetchAllForEmailAddress" should {

    "retrieve all the applications for a given collaborator email address" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchAllForEmailAddress("user@example.com"))

      retrieved shouldBe Seq(application1, application2)
    }

  }

  "fetchStandardNonTestingApps" should {
    "retrieve all the standard applications not in TESTING state" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, state = pendingRequesterVerificationState("user1"))
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, state = productionState("user2"))
      val application4 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, state = pendingRequesterVerificationState("user2"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))

      val retrieved = await(applicationRepository.fetchStandardNonTestingApps())

      retrieved.toSet shouldBe Set(application2, application3, application4)
    }

    "return empty list when no apps are found" in {
      await(applicationRepository.fetchStandardNonTestingApps()) shouldBe Nil
    }

    "not return Privileged applications" in {
      val application1 = anApplicationData(UUID.randomUUID(), state = productionState("gatekeeper"), access = Privileged())
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) shouldBe Nil
    }

    "not return ROPC applications" in {
      val application1 = anApplicationData(UUID.randomUUID(), state = productionState("gatekeeper"), access = Ropc())
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) shouldBe Nil
    }

    "return empty list when all apps  in TESTING state" in {
      val application1 = anApplicationData(UUID.randomUUID())
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) shouldBe Nil
    }
  }

  "fetchNonTestingApplicationByName" should {

    "retrieve the application with the matching name" in {
      val applicationName = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationData(id = UUID.randomUUID())
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetchApplicationsByName(applicationName))

      retrieved shouldBe Seq(application)
    }

    "dont retrieve the application if it's a non-matching name" in {
      val applicationNormalisedName = "appname"

      val application = anApplicationData(id = UUID.randomUUID())
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetchApplicationsByName("non-matching-name"))

      retrieved shouldBe Seq.empty
    }
  }

  "fetchAllByStatusDetails" should {

    val dayOfExpiry = HmrcTime.now

    val expiryOnTheDayBefore = dayOfExpiry.minusDays(1)

    val expiryOnTheDayAfter = dayOfExpiry.plusDays(1)


    def verifyApplications(responseApplications: Seq[ApplicationData], expectedState: State.State, expectedNumber: Int): Unit = {
      responseApplications.foreach(app => app.state.name shouldBe expectedState)
      withClue(s"The expected number of applications with state $expectedState is $expectedNumber") {
        responseApplications.size shouldBe expectedNumber
      }
    }


    "retrieve the only application with PENDING_REQUESTER_VERIFICATION state that have been updated before the expiryDay" in {
      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, expiryOnTheDayBefore),
        createAppWithStatusUpdatedOn(State.PENDING_GATEKEEPER_APPROVAL, expiryOnTheDayBefore),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, expiryOnTheDayBefore),
        createAppWithStatusUpdatedOn(State.PRODUCTION, expiryOnTheDayBefore)
      )
      applications.foreach(application => await(applicationRepository.save(application)))

      verifyApplications(
        await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)),
        State.PENDING_REQUESTER_VERIFICATION,
        1)
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated before the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, expiryOnTheDayBefore)
      await(applicationRepository.save(application))

      verifyApplications(
        await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)),
        State.PENDING_REQUESTER_VERIFICATION,
        1)
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated on the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)
      await(applicationRepository.save(application))

      verifyApplications(
        await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)),
        State.PENDING_REQUESTER_VERIFICATION,
        1)
    }

    "retrieve no application with PENDING_REQUESTER_VERIFICATION state that have been updated after the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, expiryOnTheDayAfter)
      await(applicationRepository.save(application))

      verifyApplications(
        await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)),
        State.PENDING_REQUESTER_VERIFICATION,
        0)
    }

  }

  "fetchVerifiableBy" should {

    "retrieve the application with verificationCode when in pendingRequesterVerification state" in {
      val application = anApplicationData(UUID.randomUUID(), state = pendingRequesterVerificationState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode))
      retrieved shouldBe Some(application)
    }

    "retrieve the application with verificationCode when in production state" in {
      val application = anApplicationData(UUID.randomUUID(), state = productionState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode))
      retrieved shouldBe Some(application)
    }

    "not retrieve the application with an unknown verificationCode" in {
      val application = anApplicationData(UUID.randomUUID(), state = pendingRequesterVerificationState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy("aDifferentVerificationCode"))
      retrieved shouldBe None
    }

  }

  "delete" should {

    "delete an application from the database" in {

      val application = anApplicationData(UUID.randomUUID())

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get

      retrieved shouldBe application

      await(applicationRepository.delete(application.id))

      val result = await(applicationRepository.fetch(application.id))

      result shouldBe None
    }

  }

  "fetchAllWithNoSubscriptions" should {
    "fetch only those applications with no subscriptions" in {

      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version", application1.id)))

      val result = await(applicationRepository.fetchAllWithNoSubscriptions())

      result shouldBe Seq(application2)
    }
  }

  "fetchAll" should {
    "fetch all existing applications" in {

      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(applicationRepository.fetchAll()) shouldBe Seq(application1, application2)
    }
  }

  "fetchAllForContext" should {
    "fetch only those applications when the context matches" in {

      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id)))
      await(subscriptionRepository.insert(aSubscriptionData("other", "version-2", application3.id)))

      val result = await(applicationRepository.fetchAllForContext("context"))

      result shouldBe Seq(application1, application2)
    }
  }

  "fetchAllForApiIdentifier" should {
    "fetch only those applications when the context and version matches" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id)))
      await(subscriptionRepository.insert(aSubscriptionData("other", "version-2", application2.id, application3.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier(APIIdentifier("context", "version-2")))

      result shouldBe Seq(application2)
    }

    "fetch multiple applications with the same matching context and versions" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id, application3.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier(APIIdentifier("context", "version-2")))

      result shouldBe Seq(application2, application3)
    }

    "fetch no applications when the context and version do not match" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id)))
      await(subscriptionRepository.insert(aSubscriptionData("other", "version-2", application1.id, application2.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier(APIIdentifier("other", "version-1")))

      result shouldBe Seq.empty
    }
  }

  "The 'application' collection" should {
    "have all the current indexes" in {

      val expectedIndexes = Set(
        Index(key = Seq("_id" -> Ascending), name = Some("_id_"), unique = false, background = false),
        Index(key = Seq("state.verificationCode" -> Ascending), name = Some("verificationCodeIndex"), background = true),
        Index(key = Seq("state.name" -> Ascending, "state.updatedOn" -> Ascending), name = Some("stateName_stateUpdatedOn_Index"), background = true),
        Index(key = Seq("id" -> Ascending), name = Some("applicationIdIndex"), unique = true, background = true),
        Index(key = Seq("normalisedName" -> Ascending), name = Some("applicationNormalisedNameIndex"), background = true),
        Index(key = Seq("lastAccess" -> Ascending), name = Some("lastAccessIndex"), unique = false, background = true),
        Index(key = Seq("tokens.production.clientId" -> Ascending), name = Some("productionTokenClientIdIndex"), unique = true, background = true),
        Index(key = Seq("access.overrides" -> Ascending), name = Some("accessOverridesIndex"), background = true),
        Index(key = Seq("access.accessType" -> Ascending), name = Some("accessTypeIndex"), background = true),
        Index(key = Seq("collaborators.emailAddress" -> Ascending), name = Some("collaboratorsEmailAddressIndex"), background = true)
      )

      verifyIndexesVersionAgnostic(applicationRepository, expectedIndexes)
    }
  }

  "Search" should {
    "correctly include the skip and limit clauses" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      val applicationSearch = new ApplicationSearch(pageNumber = 2, pageSize = 1, filters = Seq.empty)

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 3
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 3
      result.applications.size shouldBe 1 // as a result of pageSize = 1
      result.applications.head.id shouldBe application2.id // as a result of pageNumber = 2
    }

    "return applications based on application state filter" in {
      val applicationInTest = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val applicationInProduction = createAppWithStatusUpdatedOn(State.PRODUCTION, DateTime.now())
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationInProduction))

      val applicationSearch = new ApplicationSearch(filters = Seq(Active))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationInProduction.id
    }

    "return applications based on access type filter" in {
      val standardApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val ropcApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, access = Ropc())
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(filters = Seq(ROPCAccess))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe ropcApplication.id
    }

    "return applications with no API subscriptions" in {
      val applicationWithSubscriptions = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val applicationWithoutSubscriptions = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(applicationWithSubscriptions))
      await(applicationRepository.save(applicationWithoutSubscriptions))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", applicationWithSubscriptions.id)))

      val applicationSearch = new ApplicationSearch(filters = Seq(NoAPISubscriptions))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationWithoutSubscriptions.id
    }

    "return applications with any API subscriptions" in {
      val applicationWithSubscriptions = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val applicationWithoutSubscriptions = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(applicationWithSubscriptions))
      await(applicationRepository.save(applicationWithoutSubscriptions))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", applicationWithSubscriptions.id)))

      val applicationSearch = ApplicationSearch(filters = Seq(OneOrMoreAPISubscriptions))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationWithSubscriptions.id
    }

    "return applications with search text matching application id" in {
      val applicationId = UUID.randomUUID()
      val applicationName = "Test Application 1"

      val application = aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId)
      val randomOtherApplication = anApplicationData(UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = Seq(ApplicationTextSearch), textToSearch = Some(applicationId.toString))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with search text matching application name" in {
      val applicationId = UUID.randomUUID()
      val applicationName = "Test Application 2"

      val application = aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId)
      val randomOtherApplication = anApplicationData(UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = Seq(ApplicationTextSearch), textToSearch = Some(applicationName))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with search text matching client id" in {
      val applicationId = UUID.randomUUID()
      val clientId = generateClientId
      val applicationName = "Test Application"

      val application = aNamedApplicationData(applicationId, applicationName, prodClientId = clientId)
      val randomOtherApplication = anApplicationData(UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = Seq(ApplicationTextSearch), textToSearch = Some(clientId.toString))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.tokens.production.clientId shouldBe clientId
    }

    "return applications with matching search text and other filters" in {
      val applicationName = "Test Application"

      // Applications with the same name, but different access levels
      val standardApplication =
        aNamedApplicationData(id = UUID.randomUUID(), applicationName, prodClientId = generateClientId)
      val ropcApplication =
        aNamedApplicationData(id = UUID.randomUUID(), applicationName, prodClientId = generateClientId, access = Ropc())
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(filters = Seq(ROPCAccess), textToSearch = Some(applicationName))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      // Only ROPC application should be returned
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe ropcApplication.id
    }

    "return applications matching search text in a case-insensitive manner" in {
      val applicationId = UUID.randomUUID()

      val application = aNamedApplicationData(applicationId, "TEST APPLICATION", prodClientId = generateClientId)
      val randomOtherApplication = anApplicationData(UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = Seq(ApplicationTextSearch), textToSearch = Some("application"))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with terms of use agreed" in {
      val applicationId = UUID.randomUUID()
      val applicationName = "Test Application"
      val termsOfUseAgreement = TermsOfUseAgreement("a@b.com", HmrcTime.now, "v1")
      val checkInformation = CheckInformation(termsOfUseAgreements = Seq(termsOfUseAgreement))

      val applicationWithTermsOfUseAgreed =
        aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId, checkInformation = Some(checkInformation))
      val applicationWithNoTermsOfUseAgreed = anApplicationData(UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(applicationWithTermsOfUseAgreed))
      await(applicationRepository.save(applicationWithNoTermsOfUseAgreed))

      val applicationSearch = new ApplicationSearch(filters = Seq(TermsOfUseAccepted))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with terms of use not agreed where checkInformation value does not exist in database" in {
      val applicationId = UUID.randomUUID()
      val applicationName = "Test Application"
      val termsOfUseAgreement = TermsOfUseAgreement("a@b.com", HmrcTime.now, "v1")
      val checkInformation = CheckInformation(termsOfUseAgreements = Seq(termsOfUseAgreement))

      val applicationWithNoCheckInformation =
        aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId)
      val applicationWithTermsOfUseAgreed =
        anApplicationData(UUID.randomUUID(), prodClientId = generateClientId, checkInformation = Some(checkInformation))
      await(applicationRepository.save(applicationWithNoCheckInformation))
      await(applicationRepository.save(applicationWithTermsOfUseAgreed))

      val applicationSearch = new ApplicationSearch(filters = Seq(TermsOfUseNotAccepted))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with terms of use not agreed where termsOfUseAgreements array is empty in database" in {
      val applicationId = UUID.randomUUID()
      val applicationName = "Test Application"
      val termsOfUseAgreement = TermsOfUseAgreement("a@b.com", HmrcTime.now, "v1")
      val checkInformation = CheckInformation(termsOfUseAgreements = Seq(termsOfUseAgreement))

      val emptyCheckInformation = CheckInformation(termsOfUseAgreements = Seq.empty)

      val applicationWithNoTermsOfUseAgreed =
        aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId, checkInformation = Some(emptyCheckInformation))
      val applicationWithTermsOfUseAgreed =
        anApplicationData(UUID.randomUUID(), prodClientId = generateClientId, checkInformation = Some(checkInformation))
      await(applicationRepository.save(applicationWithNoTermsOfUseAgreed))
      await(applicationRepository.save(applicationWithTermsOfUseAgreed))

      val applicationSearch = new ApplicationSearch(filters = Seq(TermsOfUseNotAccepted))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications subscribing to a specific API" in {
      val expectedAPIContext = "match-this-api"
      val otherAPIContext = "do-not-match-this-api"

      val expectedApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val otherApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(expectedApplication))
      await(applicationRepository.save(otherApplication))
      await(subscriptionRepository.insert(aSubscriptionData(expectedAPIContext, "version-1", expectedApplication.id)))
      await(subscriptionRepository.insert(aSubscriptionData(otherAPIContext, "version-1", otherApplication.id)))

      val applicationSearch = new ApplicationSearch(filters = Seq(SpecificAPISubscription), apiContext = Some(expectedAPIContext), apiVersion = Some(""))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe expectedApplication.id
    }

    "return applications subscribing to a specific version of an API" in {
      val apiContext = "match-this-api"
      val expectedAPIVersion = "version-1"
      val otherAPIVersion = "version-2"

      val expectedApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val otherApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      await(applicationRepository.save(expectedApplication))
      await(applicationRepository.save(otherApplication))
      await(subscriptionRepository.insert(aSubscriptionData(apiContext, expectedAPIVersion, expectedApplication.id)))
      await(subscriptionRepository.insert(aSubscriptionData(apiContext, otherAPIVersion, otherApplication.id)))

      val applicationSearch =
        new ApplicationSearch(filters = Seq(SpecificAPISubscription), apiContext = Some(apiContext), apiVersion = Some(expectedAPIVersion))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe expectedApplication.id
    }

    "return applications sorted by name ascending" in {
      val firstName = "AAA first"
      val secondName = "ZZZ second"
      val firstApplication =
        aNamedApplicationData(id = UUID.randomUUID(), name = firstName, prodClientId = generateClientId)
      val secondApplication =
        aNamedApplicationData(id = UUID.randomUUID(), name = secondName, prodClientId = generateClientId)

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch = new ApplicationSearch(sort = NameAscending)
      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 2
      result.applications.size shouldBe 2
      result.applications.head.name shouldBe firstName
      result.applications.last.name shouldBe secondName
    }

    "return applications sorted by name descending" in {
      val firstName = "AAA first"
      val secondName = "ZZZ second"
      val firstApplication =
        aNamedApplicationData(id = UUID.randomUUID(), name = firstName, prodClientId = generateClientId)
      val secondApplication =
        aNamedApplicationData(id = UUID.randomUUID(), name = secondName, prodClientId = generateClientId)

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val applicationSearch = new ApplicationSearch(sort = NameDescending)
      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 2
      result.applications.size shouldBe 2
      result.applications.head.name shouldBe secondName
      result.applications.last.name shouldBe firstName
    }

    "return applications sorted by submitted ascending" in {
      val firstCreatedOn = HmrcTime.now.minusDays(2)
      val secondCreatedOn = HmrcTime.now.minusDays(1)
      val firstApplication =
        anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId).copy(createdOn = firstCreatedOn)
      val secondApplication =
        anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId).copy(createdOn = secondCreatedOn)

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch = new ApplicationSearch(sort = SubmittedAscending)
      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 2
      result.applications.size shouldBe 2
      result.applications.head.createdOn shouldBe firstCreatedOn
      result.applications.last.createdOn shouldBe secondCreatedOn
    }

    "return applications sorted by submitted descending" in {
      val firstCreatedOn = HmrcTime.now.minusDays(2)
      val secondCreatedOn = HmrcTime.now.minusDays(1)
      val firstApplication =
        anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId).copy(createdOn = firstCreatedOn)
      val secondApplication =
        anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId).copy(createdOn = secondCreatedOn)

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val applicationSearch = new ApplicationSearch(sort = SubmittedDescending)
      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 2
      result.applications.size shouldBe 2
      result.applications.head.createdOn shouldBe secondCreatedOn
      result.applications.last.createdOn shouldBe firstCreatedOn
    }
  }

  "processAll" should {
    class TestService {
      def doSomething(application: ApplicationData): ApplicationData = application
    }

    "ensure function is called for every Application in collection" in {
      val firstApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val secondApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val mockTestService = mock[TestService]

      await(applicationRepository.processAll(a => mockTestService.doSomething(a)))

      verify(mockTestService, times(1)).doSomething(firstApplication)
      verify(mockTestService, times(1)).doSomething(secondApplication)
      verifyNoMoreInteractions(mockTestService)
    }
  }

  "ApplicationWithSubscriptionCount" should {
    "return Applications with a count of subscriptions" in {
      val api1 = "api-1"
      val api2 = "api-2"
      val api3 = "api-3"
      val api1Version = "api-1-version-1"
      val api2Version = "api-2-version-2"
      val api3Version = "api-3-version-3"

      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      await(subscriptionRepository.insert(aSubscriptionData(api1, api1Version, application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData(api2, api2Version, application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData(api3, api3Version, application2.id)))

      val result = await(applicationRepository.getApplicationWithSubscriptionCount())

      result.get(s"applicationsWithSubscriptionCount.${application1.name}") shouldBe Some(2)
      result.get(s"applicationsWithSubscriptionCount.${application2.name}") shouldBe Some(1)
      result.get(s"applicationsWithSubscriptionCount.${application3.name}") shouldBe None
    }

    "return Applications when more than 100 results bug" in {
      (1 to 200).foreach(i => {
        val api = s"api-$i"
        val apiVersion = s"api-$i-version-$i"

        val application = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId)
        await(applicationRepository.save(application))

        await(subscriptionRepository.insert(aSubscriptionData(api, apiVersion, application.id)))
      })

      val result = await(applicationRepository.getApplicationWithSubscriptionCount())

      result.keys.count(_ => true) shouldBe 200
    }
  }

  def createAppWithStatusUpdatedOn(state: State.State, updatedOn: DateTime) = anApplicationData(
    id = UUID.randomUUID(),
    prodClientId = generateClientId,
    state = ApplicationState(state, Some("requestorEmail@example.com"), Some("aVerificationCode"), updatedOn)
  )

  def aSubscriptionData(context: String, version: String, applicationIds: UUID*) = {
    SubscriptionData(APIIdentifier(context, version), Set(applicationIds: _*))
  }

  def anApplicationData(id: UUID,
                        prodClientId: String = "aaa",
                        state: ApplicationState = testingState(),
                        access: Access = Standard(Seq.empty, None, None),
                        user: String = "user@example.com",
                        checkInformation: Option[CheckInformation] = None): ApplicationData = {

    aNamedApplicationData(id, s"myApp-$id", prodClientId, state, access, user, checkInformation)
  }

  def aNamedApplicationData(id: UUID,
                            name: String,
                            prodClientId: String = "aaa",
                            state: ApplicationState = testingState(),
                            access: Access = Standard(Seq.empty, None, None),
                            user: String = "user@example.com",
                            checkInformation: Option[CheckInformation] = None): ApplicationData = {

    ApplicationData(
      id,
      name,
      name.toLowerCase,
      Set(Collaborator(user, Role.ADMINISTRATOR)),
      Some("description"),
      "username",
      "password",
      "myapplication",
      ApplicationTokens(EnvironmentToken(prodClientId, generateClientSecret, generateAccessToken)),
      state,
      access,
      HmrcTime.now,
      Some(HmrcTime.now),
      checkInformation = checkInformation)
  }

}
