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

package uk.gov.hmrc.thirdpartyapplication.repository

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import org.joda.time.DateTime
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, MetricsHelper}
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random.nextString
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientId

class ApplicationRepositorySpec
  extends AsyncHmrcSpec
    with MongoSpecSupport
    with BeforeAndAfterEach with BeforeAndAfterAll
    with ApplicationStateUtil
    with IndexVerification
    with MetricsHelper {

  val DEFAULT_GRANT_LENGTH = 547
  val newGrantLength = 1000
  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val applicationRepository = new ApplicationRepository(reactiveMongoComponent)
  private val subscriptionRepository = new SubscriptionRepository(reactiveMongoComponent)

  private def generateClientId = {
    ClientId.random
  }

  private def generateAccessToken = {
    val lengthOfRandomToken = 5
    nextString(lengthOfRandomToken)
  }

  override def beforeEach() {
    List(applicationRepository, subscriptionRepository).foreach { db =>
      await(db.drop)
      await(db.ensureIndexes)
    }
  }

  override protected def afterAll() {
    List(applicationRepository, subscriptionRepository).foreach { db =>
      await(db.drop)
    }
  }

  "save" should {

    "create an application and retrieve it from database" in {

      val application = anApplicationData(ApplicationId.random)

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get

      retrieved shouldBe application
    }

    "update an application" in {

      val application = anApplicationData(ApplicationId.random)

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
      val applicationId = ApplicationId.random
      await(
        applicationRepository.save(
          anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com")).copy(rateLimitTier = Some(RateLimitTier.BRONZE))))

      val updatedRateLimit = RateLimitTier.GOLD

      val updatedApplication = await(applicationRepository.updateApplicationRateLimit(applicationId, updatedRateLimit))

      updatedApplication.rateLimitTier shouldBe Some(updatedRateLimit)
    }

    "set the grant Length field on an Application document" in {
      val applicationId = ApplicationId.random
      await(
        applicationRepository.save(
          anApplicationData(applicationId, ClientId("aaa"))))

      val newRetrieved = await(applicationRepository.fetch(applicationId)).get

      newRetrieved.grantLength shouldBe DEFAULT_GRANT_LENGTH
    }

    "set the rateLimitTier field on an Application document where none previously existed" in {
      val applicationId = ApplicationId.random
      await(
        applicationRepository.save(
          anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com")).copy(rateLimitTier = None)))

      val updatedRateLimit = RateLimitTier.GOLD

      val updatedApplication = await(applicationRepository.updateApplicationRateLimit(applicationId, updatedRateLimit))

      updatedApplication.rateLimitTier shouldBe Some(updatedRateLimit)
    }
  }

  "updateApplicationIpAllowlist" should {
    "set the ipAllowlist fields on an Application document" in {
      val applicationId = ApplicationId.random
      await(applicationRepository.save(anApplicationData(applicationId)))
      val updatedIpAllowlist = IpAllowlist(required = true, Set("192.168.100.0/22", "192.168.104.1/32"))

      val updatedApplication = await(applicationRepository.updateApplicationIpAllowlist(applicationId, updatedIpAllowlist))

      updatedApplication.ipAllowlist shouldBe updatedIpAllowlist
    }
  }

  "recordApplicationUsage" should {
    "update the lastAccess property" in {
      val testStartTime = DateTime.now()

      val applicationId = ApplicationId.random

      val application =
        anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com"))
          .copy(lastAccess = Some(DateTime.now.minusDays(20))) // scalastyle:ignore magic.number

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.recordApplicationUsage(applicationId))

      retrieved.lastAccess.get.isAfter(testStartTime) shouldBe true
    }

    "update the grantLength property" in {

      val applicationId = ApplicationId.random

      val application =
        anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com"), grantLength = newGrantLength )
          .copy(lastAccess = Some(DateTime.now.minusDays(20))) // scalastyle:ignore magic.number

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.recordApplicationUsage(applicationId))

      retrieved.grantLength shouldBe newGrantLength
    }
  }

  "recordServerTokenUsage" should {
    "update the lastAccess and lastAccessTokenUsage properties" in {
      val testStartTime = DateTime.now()
      val applicationId = ApplicationId.random
      val application =
        anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com"))
          .copy(lastAccess = Some(DateTime.now.minusDays(20))) // scalastyle:ignore magic.number
      application.tokens.production.lastAccessTokenUsage shouldBe None
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.recordServerTokenUsage(applicationId))

      retrieved.lastAccess.get.isAfter(testStartTime) shouldBe true
      retrieved.tokens.production.lastAccessTokenUsage.get.isAfter(testStartTime) shouldBe true
    }
  }

  "recordClientSecretUsage" should {
    "create a lastAccess property for client secret if it does not already exist" in {
      val testStartTime = DateTime.now()
      val applicationId = ApplicationId.random
      val application = anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com"))
      val generatedClientSecretId = application.tokens.production.clientSecrets.head.id

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.recordClientSecretUsage(applicationId, generatedClientSecretId))

      application.tokens.production.clientSecrets.head.lastAccess shouldBe None // Original object has no value
      retrieved.tokens.production.clientSecrets.head.lastAccess.get.isAfter(testStartTime) shouldBe true // Retrieved object is updated
    }

    "update an existing lastAccess property for a client secret" in {
      val testStartTime = DateTime.now()
      val applicationId = ApplicationId.random
      val applicationTokens =
        ApplicationTokens(
          Token(
            ClientId("aaa"),
            generateAccessToken,
            List(ClientSecret(name = "Default", lastAccess = Some(DateTime.now.minusDays(20)), hashedSecret = "hashed-secret"))))
      val application = anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com")).copy(tokens = applicationTokens)
      val generatedClientSecretId = application.tokens.production.clientSecrets.head.id

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.recordClientSecretUsage(applicationId, generatedClientSecretId))

      retrieved.tokens.production.clientSecrets.head.lastAccess.get.isAfter(testStartTime) shouldBe true
      retrieved.tokens.production.clientSecrets.head.lastAccess.get.isAfter(applicationTokens.production.clientSecrets.head.lastAccess.get) shouldBe true
    }

    "update the correct client secret when there are multiple" in {
      val testStartTime = DateTime.now()
      val applicationId = ApplicationId.random
      val secretToUpdate =
        ClientSecret(name = "SecretToUpdate", lastAccess = Some(DateTime.now.minusDays(20)), hashedSecret = "hashed-secret")
      val applicationTokens =
        ApplicationTokens(
          Token(
            ClientId("aaa"),
            generateAccessToken,
            List(
              secretToUpdate,
              ClientSecret(name = "SecretToLeave", lastAccess = Some(DateTime.now.minusDays(20)), hashedSecret = "hashed-secret"))))
      val application = anApplicationData(applicationId, ClientId("aaa"), productionState("requestorEmail@example.com")).copy(tokens = applicationTokens)

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.recordClientSecretUsage(applicationId, secretToUpdate.id))

      retrieved.tokens.production.clientSecrets.foreach(retrievedClientSecret =>
        if(retrievedClientSecret.id == secretToUpdate.id)
          retrievedClientSecret.lastAccess.get.isAfter(testStartTime) shouldBe true
        else
          retrievedClientSecret.lastAccess.get.isBefore(testStartTime) shouldBe true
      )
    }
  }

  "fetchByClientId" should {

    "retrieve the application for a given client id when it has a matching client id" in {

      val application1 = anApplicationData(ApplicationId.random, ClientId("aaa"), productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(ApplicationId.random, ClientId("zzz"), productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByClientId(application2.tokens.production.clientId))

      retrieved shouldBe Some(application2)

    }

  }

  "fetchByServerToken" should {

    "retrieve the application when it is matched for access token" in {

      val application1 = anApplicationData(ApplicationId.random, ClientId("aaa"), productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(ApplicationId.random, ClientId("zzz"), productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByServerToken(application2.tokens.production.accessToken))

      retrieved shouldBe Some(application2)
    }

    "retrieve the grant length for an application for a given client id when it has a matching client id" in {

      val application1 = anApplicationData(ApplicationId.random, ClientId("aaa"), productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(ApplicationId.random, ClientId("zzz"), productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByClientId(application2.tokens.production.clientId))

      retrieved.map(_.grantLength) shouldBe Some(DEFAULT_GRANT_LENGTH)

    }
  }

  "fetchAllForEmailAddress" should {

    "retrieve all the applications for a given collaborator email address" in {
      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchAllForEmailAddress("user@example.com"))

      retrieved shouldBe List(application1, application2)
    }

  }

  "fetchStandardNonTestingApps" should {
    "retrieve all the standard applications not in TESTING state" in {
      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId, state = pendingRequesterVerificationState("user1"))
      val application3 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId, state = productionState("user2"))
      val application4 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId, state = pendingRequesterVerificationState("user2"))

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
      val application1 = anApplicationData(ApplicationId.random, state = productionState("gatekeeper"), access = Privileged())
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) shouldBe Nil
    }

    "not return ROPC applications" in {
      val application1 = anApplicationData(ApplicationId.random, state = productionState("gatekeeper"), access = Ropc())
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) shouldBe Nil
    }

    "return empty list when all apps  in TESTING state" in {
      val application1 = anApplicationData(ApplicationId.random)
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) shouldBe Nil
    }
  }

  "fetchNonTestingApplicationByName" should {

    "retrieve the application with the matching name" in {
      val applicationName = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationData(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetchApplicationsByName(applicationName))

      retrieved shouldBe List(application)
    }

    "dont retrieve the application if it's a non-matching name" in {
      val applicationNormalisedName = "appname"

      val application = anApplicationData(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetchApplicationsByName("non-matching-name"))

      retrieved shouldBe List.empty
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
      val application = anApplicationData(ApplicationId.random, state = pendingRequesterVerificationState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode))
      retrieved shouldBe Some(application)
    }

    "retrieve the application with verificationCode when in production state" in {
      val application = anApplicationData(ApplicationId.random, state = productionState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode))
      retrieved shouldBe Some(application)
    }

    "not retrieve the application with an unknown verificationCode" in {
      val application = anApplicationData(ApplicationId.random, state = pendingRequesterVerificationState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy("aDifferentVerificationCode"))
      retrieved shouldBe None
    }

  }

  "delete" should {

    "delete an application from the database" in {

      val application = anApplicationData(ApplicationId.random)

      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get

      retrieved shouldBe application

      await(applicationRepository.delete(application.id))

      val result = await(applicationRepository.fetch(application.id))

      result shouldBe None
    }

  }

  "fetch" should {

    // API-3862: The wso2Username and wso2Password fields have been removed from ApplicationData, but will still exist in Mongo for most applications
    // Test that documents are still correctly deserialised into ApplicationData objects
    "retrieve an application when wso2Username and wso2Password exist" in {
      val applicationId = ApplicationId.random
      val application = anApplicationData(applicationId)

      await(applicationRepository.save(application))
      await(applicationRepository.findAndUpdate(Json.obj("id" -> applicationId.value.toString), Json.obj("$set" -> Json.obj("wso2Username" -> "legacyUsername"))))
      await(applicationRepository.findAndUpdate(Json.obj("id" -> applicationId.value.toString), Json.obj("$set" -> Json.obj("wso2Password" -> "legacyPassword"))))

      val result = await(applicationRepository.fetch(applicationId))

      result should not be None
    }
  }

  "fetchAllWithNoSubscriptions" should {
    "fetch only those applications with no subscriptions" in {

      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version", application1.id)))

      val result = await(applicationRepository.fetchAllWithNoSubscriptions())

      result shouldBe List(application2)
    }
  }

  "fetchAll" should {
    "fetch all existing applications" in {

      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(applicationRepository.fetchAll()) shouldBe List(application1, application2)
    }
  }

  "fetchAllForContext" should {
    "fetch only those applications when the context matches" in {

      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application3 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id)))
      await(subscriptionRepository.insert(aSubscriptionData("other", "version-2", application3.id)))

      val result = await(applicationRepository.fetchAllForContext("context".asContext))

      result shouldBe List(application1, application2)
    }
  }

  "fetchAllForApiIdentifier" should {
    "fetch only those applications when the context and version matches" in {
      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application3 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id)))
      await(subscriptionRepository.insert(aSubscriptionData("other", "version-2", application2.id, application3.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier("context".asIdentifier("version-2")))

      result shouldBe List(application2)
    }

    "fetch multiple applications with the same matching context and versions" in {
      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application3 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id, application3.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier("context".asIdentifier("version-2")))

      result shouldBe List(application2, application3)
    }

    "fetch no applications when the context and version do not match" in {
      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id)))
      await(subscriptionRepository.insert(aSubscriptionData("other", "version-2", application1.id, application2.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier("other".asIdentifier("version-1")))

      result shouldBe List.empty
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
    def applicationWithLastAccessDate(applicationId: ApplicationId, lastAccessDate: DateTime): ApplicationData =
      anApplicationData(id = applicationId, prodClientId = generateClientId).copy(lastAccess = Some(lastAccessDate))

    "correctly include the skip and limit clauses" in {
      val application1 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application2 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val application3 = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      val applicationSearch = new ApplicationSearch(pageNumber = 2, pageSize = 1, filters = List.empty)

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 3
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 3
      result.applications.size shouldBe 1 // as a result of pageSize = 1
      result.applications.head.id shouldBe application2.id // as a result of pageNumber = 2
    }

    "return applications based on application state filter" in {
      val applicationInTest = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val applicationInProduction = createAppWithStatusUpdatedOn(State.PRODUCTION, DateTime.now())
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationInProduction))

      val applicationSearch = new ApplicationSearch(filters = List(Active))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationInProduction.id
    }

    "return applications based on access type filter" in {
      val standardApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val ropcApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId, access = Ropc())
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ROPCAccess))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe ropcApplication.id
    }

    "return applications with no API subscriptions" in {
      val applicationWithSubscriptions = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val applicationWithoutSubscriptions = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(applicationWithSubscriptions))
      await(applicationRepository.save(applicationWithoutSubscriptions))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", applicationWithSubscriptions.id)))

      val applicationSearch = new ApplicationSearch(filters = List(NoAPISubscriptions))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationWithoutSubscriptions.id
    }

    "return applications with any API subscriptions" in {
      val applicationWithSubscriptions = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val applicationWithoutSubscriptions = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(applicationWithSubscriptions))
      await(applicationRepository.save(applicationWithoutSubscriptions))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", applicationWithSubscriptions.id)))

      val applicationSearch = ApplicationSearch(filters = List(OneOrMoreAPISubscriptions))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationWithSubscriptions.id
    }

    "return applications with search text matching application id" in {
      val applicationId = ApplicationId.random
      val applicationName = "Test Application 1"

      val application = aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId)
      val randomOtherApplication = anApplicationData(ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ApplicationTextSearch), textToSearch = Some(applicationId.value.toString))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with search text matching application name" in {
      val applicationId = ApplicationId.random
      val applicationName = "Test Application 2"

      val application = aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId)
      val randomOtherApplication = anApplicationData(ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ApplicationTextSearch), textToSearch = Some(applicationName))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with search text matching client id" in {
      val applicationId = ApplicationId.random
      val clientId = generateClientId
      val applicationName = "Test Application"

      val application = aNamedApplicationData(applicationId, applicationName, prodClientId = clientId)
      val randomOtherApplication = anApplicationData(ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ApplicationTextSearch), textToSearch = Some(clientId.value))

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
        aNamedApplicationData(id = ApplicationId.random, applicationName, prodClientId = generateClientId)
      val ropcApplication =
        aNamedApplicationData(id = ApplicationId.random, applicationName, prodClientId = generateClientId, access = Ropc())
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ROPCAccess), textToSearch = Some(applicationName))

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
      val applicationId = ApplicationId.random

      val application = aNamedApplicationData(applicationId, "TEST APPLICATION", prodClientId = generateClientId)
      val randomOtherApplication = anApplicationData(ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ApplicationTextSearch), textToSearch = Some("application"))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with terms of use agreed" in {
      val applicationId = ApplicationId.random
      val applicationName = "Test Application"
      val termsOfUseAgreement = TermsOfUseAgreement("a@b.com", HmrcTime.now, "v1")
      val checkInformation = CheckInformation(termsOfUseAgreements = List(termsOfUseAgreement))

      val applicationWithTermsOfUseAgreed =
        aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId, checkInformation = Some(checkInformation))
      val applicationWithNoTermsOfUseAgreed = anApplicationData(ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(applicationWithTermsOfUseAgreed))
      await(applicationRepository.save(applicationWithNoTermsOfUseAgreed))

      val applicationSearch = new ApplicationSearch(filters = List(TermsOfUseAccepted))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with terms of use not agreed where checkInformation value does not exist in database" in {
      val applicationId = ApplicationId.random
      val applicationName = "Test Application"
      val termsOfUseAgreement = TermsOfUseAgreement("a@b.com", HmrcTime.now, "v1")
      val checkInformation = CheckInformation(termsOfUseAgreements = List(termsOfUseAgreement))

      val applicationWithNoCheckInformation =
        aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId)
      val applicationWithTermsOfUseAgreed =
        anApplicationData(ApplicationId.random, prodClientId = generateClientId, checkInformation = Some(checkInformation))
      await(applicationRepository.save(applicationWithNoCheckInformation))
      await(applicationRepository.save(applicationWithTermsOfUseAgreed))

      val applicationSearch = new ApplicationSearch(filters = List(TermsOfUseNotAccepted))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return applications with terms of use not agreed where termsOfUseAgreements array is empty in database" in {
      val applicationId = ApplicationId.random
      val applicationName = "Test Application"
      val termsOfUseAgreement = TermsOfUseAgreement("a@b.com", HmrcTime.now, "v1")
      val checkInformation = CheckInformation(termsOfUseAgreements = List(termsOfUseAgreement))

      val emptyCheckInformation = CheckInformation(termsOfUseAgreements = List.empty)

      val applicationWithNoTermsOfUseAgreed =
        aNamedApplicationData(applicationId, applicationName, prodClientId = generateClientId, checkInformation = Some(emptyCheckInformation))
      val applicationWithTermsOfUseAgreed =
        anApplicationData(ApplicationId.random, prodClientId = generateClientId, checkInformation = Some(checkInformation))
      await(applicationRepository.save(applicationWithNoTermsOfUseAgreed))
      await(applicationRepository.save(applicationWithTermsOfUseAgreed))

      val applicationSearch = new ApplicationSearch(filters = List(TermsOfUseNotAccepted))

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

      val expectedApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val otherApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(expectedApplication))
      await(applicationRepository.save(otherApplication))
      await(subscriptionRepository.insert(aSubscriptionData(expectedAPIContext, "version-1", expectedApplication.id)))
      await(subscriptionRepository.insert(aSubscriptionData(otherAPIContext, "version-1", otherApplication.id)))

      val applicationSearch = new ApplicationSearch(filters = List(SpecificAPISubscription), apiContext = Some(expectedAPIContext.asContext), apiVersion = None)

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

      val expectedApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val otherApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      await(applicationRepository.save(expectedApplication))
      await(applicationRepository.save(otherApplication))
      await(subscriptionRepository.insert(aSubscriptionData(apiContext, expectedAPIVersion, expectedApplication.id)))
      await(subscriptionRepository.insert(aSubscriptionData(apiContext, otherAPIVersion, otherApplication.id)))

      val applicationSearch =
        new ApplicationSearch(filters = List(SpecificAPISubscription), apiContext = Some(apiContext.asContext), apiVersion = Some(expectedAPIVersion.asVersion))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe expectedApplication.id
    }

    "return applications last used before a certain date" in {
      val oldApplicationId = ApplicationId.random
      val cutoffDate = DateTime.now.minusMonths(12)

      await(applicationRepository.save(applicationWithLastAccessDate(oldApplicationId, DateTime.now.minusMonths(18))))
      await(applicationRepository.save(applicationWithLastAccessDate(ApplicationId.random, DateTime.now)))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe oldApplicationId
    }

    "include applications with no lastAccess date where they were created before cutoff date" in {
      val oldApplicationId = ApplicationId.random
      val oldApplication = anApplicationData(oldApplicationId).copy(createdOn = DateTime.now.minusMonths(18), lastAccess = None)
      val cutoffDate = DateTime.now.minusMonths(12)

      await(applicationRepository.save(oldApplication))
      await(applicationRepository.save(applicationWithLastAccessDate(ApplicationId.random, DateTime.now)))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe oldApplicationId
    }

    "return applications that are equal to the specified cutoff date when searching for older applications" in {
      val oldApplicationId = ApplicationId.random
      val cutoffDate = DateTime.now.minusMonths(12)

      await(applicationRepository.save(applicationWithLastAccessDate(oldApplicationId, cutoffDate)))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 1
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe oldApplicationId
    }

    "return no results if no applications are last used before the cutoff date" in {
      val cutoffDate = DateTime.now.minusMonths(12)
      await(applicationRepository.save(applicationWithLastAccessDate(ApplicationId.random, DateTime.now)))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.applications.size shouldBe 0
    }

    "return applications last used after a certain date" in {
      val newerApplicationId = ApplicationId.random
      val cutoffDate = DateTime.now.minusMonths(12)

      await(applicationRepository.save(applicationWithLastAccessDate(newerApplicationId, DateTime.now)))
      await(applicationRepository.save(applicationWithLastAccessDate(ApplicationId.random, DateTime.now.minusMonths(18))))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe newerApplicationId
    }

    "include applications with no lastAccess date where they were created after cutoff date" in {
      val newerApplicationId = ApplicationId.random
      val newerApplication = anApplicationData(newerApplicationId).copy(lastAccess = None)
      val cutoffDate = DateTime.now.minusMonths(12)

      await(applicationRepository.save(newerApplication))
      await(applicationRepository.save(applicationWithLastAccessDate(ApplicationId.random, DateTime.now.minusMonths(18))))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe newerApplicationId
    }

    "return applications that are equal to the specified cutoff date when searching for newer applications" in {
      val applicationId = ApplicationId.random
      val cutoffDate = DateTime.now.minusMonths(12)

      await(applicationRepository.save(applicationWithLastAccessDate(applicationId, cutoffDate)))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 1
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 1
      result.applications.size shouldBe 1
      result.applications.head.id shouldBe applicationId
    }

    "return no results if no applications are last used after the cutoff date" in {
      val cutoffDate = DateTime.now
      await(applicationRepository.save(applicationWithLastAccessDate(ApplicationId.random, DateTime.now.minusMonths(6))))

      val applicationSearch = new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.applications.size shouldBe 0
    }

    "return applications sorted by name ascending" in {
      val firstName = "AAA first"
      val secondName = "ZZZ second"
      val firstApplication =
        aNamedApplicationData(id = ApplicationId.random, name = firstName, prodClientId = generateClientId)
      val secondApplication =
        aNamedApplicationData(id = ApplicationId.random, name = secondName, prodClientId = generateClientId)

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
        aNamedApplicationData(id = ApplicationId.random, name = firstName, prodClientId = generateClientId)
      val secondApplication =
        aNamedApplicationData(id = ApplicationId.random, name = secondName, prodClientId = generateClientId)

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
        anApplicationData(id = ApplicationId.random, prodClientId = generateClientId).copy(createdOn = firstCreatedOn)
      val secondApplication =
        anApplicationData(id = ApplicationId.random, prodClientId = generateClientId).copy(createdOn = secondCreatedOn)

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
        anApplicationData(id = ApplicationId.random, prodClientId = generateClientId).copy(createdOn = firstCreatedOn)
      val secondApplication =
        anApplicationData(id = ApplicationId.random, prodClientId = generateClientId).copy(createdOn = secondCreatedOn)

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

    "return applications sorted by lastAccess ascending" in {
      val mostRecentlyAccessedDate = HmrcTime.now.minusDays(1)
      val oldestLastAccessDate = HmrcTime.now.minusDays(2)
      val firstApplication = applicationWithLastAccessDate(ApplicationId.random, mostRecentlyAccessedDate)
      val secondApplication = applicationWithLastAccessDate(ApplicationId.random, oldestLastAccessDate)

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch = new ApplicationSearch(sort = LastUseDateAscending)
      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 2
      result.applications.size shouldBe 2
      result.applications.head.lastAccess shouldBe Some(oldestLastAccessDate)
      result.applications.last.lastAccess shouldBe Some(mostRecentlyAccessedDate)
    }

    "return applications sorted by lastAccess descending" in {
      val mostRecentlyAccessedDate = HmrcTime.now.minusDays(1)
      val oldestLastAccessDate = HmrcTime.now.minusDays(2)
      val firstApplication = applicationWithLastAccessDate(ApplicationId.random, mostRecentlyAccessedDate)
      val secondApplication = applicationWithLastAccessDate(ApplicationId.random, oldestLastAccessDate)

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch = new ApplicationSearch(sort = LastUseDateDescending)
      val result = await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size shouldBe 1
      result.totals.head.total shouldBe 2
      result.matching.size shouldBe 1
      result.matching.head.total shouldBe 2
      result.applications.size shouldBe 2
      result.applications.head.lastAccess shouldBe Some(mostRecentlyAccessedDate)
      result.applications.last.lastAccess shouldBe Some(oldestLastAccessDate)
    }
  }

  "processAll" should {
    class TestService {
      def doSomething(application: ApplicationData): ApplicationData = application
    }

    "ensure function is called for every Application in collection" in {
      val firstApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
      val secondApplication = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)

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

      val application1 = aNamedApplicationData(id = ApplicationId.random, name = "organisations/trusts", prodClientId = generateClientId)
      val application2 = aNamedApplicationData(id = ApplicationId.random, name = "application.com", prodClientId = generateClientId)
      val application3 = aNamedApplicationData(id = ApplicationId.random, name = "Get) Vat Done (Fast)", prodClientId = generateClientId)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      await(subscriptionRepository.insert(aSubscriptionData(api1, api1Version, application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData(api2, api2Version, application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData(api3, api3Version, application2.id)))

      val sanitisedApp1Name = sanitiseGrafanaNodeName(application1.name)
      val sanitisedApp2Name = sanitiseGrafanaNodeName(application2.name)
      val sanitisedApp3Name = sanitiseGrafanaNodeName(application3.name)

      val result = await(applicationRepository.getApplicationWithSubscriptionCount())

      result.get(s"applicationsWithSubscriptionCountV1.${sanitisedApp1Name}") shouldBe Some(2)
      result.get(s"applicationsWithSubscriptionCountV1.${sanitisedApp2Name}") shouldBe Some(1)
      result.get(s"applicationsWithSubscriptionCountV1.${sanitisedApp3Name}") shouldBe None
    }

    "return Applications when more than 100 results bug" in {
      (1 to 200).foreach(i => {
        val api = s"api-$i"
        val apiVersion = s"api-$i-version-$i"

        val application = anApplicationData(id = ApplicationId.random, prodClientId = generateClientId)
        await(applicationRepository.save(application))

        await(subscriptionRepository.insert(aSubscriptionData(api, apiVersion, application.id)))
      })

      val result = await(applicationRepository.getApplicationWithSubscriptionCount())

      result.keys.count(_ => true) shouldBe 200
    }
  }

  "addClientSecret" should {
    "append client secrets to an existing application" in {
      val applicationId = ApplicationId.random

      val savedApplication = await(applicationRepository.save(anApplicationData(applicationId)))

      val clientSecret = ClientSecret("secret-name", hashedSecret = "hashed-secret")
      val updatedApplication = await(applicationRepository.addClientSecret(applicationId, clientSecret))

      savedApplication.tokens.production.clientSecrets should not contain clientSecret
      updatedApplication.tokens.production.clientSecrets should contain (clientSecret)
    }
  }

  "updateClientSecretName" should {
    def namedClientSecret(id: String, name: String): ClientSecret = ClientSecret(id = id, name = name, hashedSecret = "hashed-secret")
    def clientSecretWithId(application: ApplicationData, clientSecretId: String): ClientSecret =
      application.tokens.production.clientSecrets.find(_.id == clientSecretId).get
    def otherClientSecrets(application: ApplicationData, clientSecretId: String): Seq[ClientSecret] =
      application.tokens.production.clientSecrets.filterNot(_.id == clientSecretId)

    "populate the name where it was an empty String" in {
      val applicationId = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      await(applicationRepository.save(anApplicationData(applicationId, clientSecrets = List(namedClientSecret(clientSecretId, "")))))

      val updatedApplication = await(applicationRepository.updateClientSecretName(applicationId, clientSecretId, "new-name"))

      clientSecretWithId(updatedApplication, clientSecretId).name should be ("new-name")
    }

    "populate the name where it was Default" in {
      val applicationId = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      await(applicationRepository.save(anApplicationData(applicationId, clientSecrets = List(namedClientSecret(clientSecretId, "Default")))))

      val updatedApplication = await(applicationRepository.updateClientSecretName(applicationId, clientSecretId, "new-name"))

      clientSecretWithId(updatedApplication, clientSecretId).name should be ("new-name")
    }

    "populate the name where it was a masked String" in {
      val applicationId = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      await(applicationRepository.save(
        anApplicationData(applicationId, clientSecrets = List(namedClientSecret(clientSecretId, "••••••••••••••••••••••••••••••••abc1")))))

      val updatedApplication = await(applicationRepository.updateClientSecretName(applicationId, clientSecretId, "new-name"))

      clientSecretWithId(updatedApplication, clientSecretId).name should be ("new-name")
    }

    "update correct client secret where there are multiple" in {
      val applicationId = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      val clientSecret1 = namedClientSecret(UUID.randomUUID().toString, "secret-that-should-not-change")
      val clientSecret2 = namedClientSecret(UUID.randomUUID().toString, "secret-that-should-not-change")
      val clientSecret3 = namedClientSecret(clientSecretId, "secret-3")

      await(applicationRepository.save(anApplicationData(applicationId, clientSecrets = List(clientSecret1, clientSecret2, clientSecret3))))

      val updatedApplication = await(applicationRepository.updateClientSecretName(applicationId, clientSecretId, "new-name"))

      clientSecretWithId(updatedApplication, clientSecretId).name should be ("new-name")
      otherClientSecrets(updatedApplication, clientSecretId) foreach { otherSecret =>
        otherSecret.name should be ("secret-that-should-not-change")
      }
    }
  }

  "updateClientSecretHash" should {
    "overwrite an existing hashedSecretField" in {
      val applicationId = ApplicationId.random
      val clientSecret = ClientSecret("secret-name", hashedSecret = "old-hashed-secret")

      val savedApplication = await(applicationRepository.save(anApplicationData(applicationId, clientSecrets = List(clientSecret))))

      val updatedApplication = await(applicationRepository.updateClientSecretHash(applicationId, clientSecret.id, "new-hashed-secret"))

      savedApplication.tokens.production.clientSecrets.head.hashedSecret should be ("old-hashed-secret")
      updatedApplication.tokens.production.clientSecrets.head.hashedSecret should be ("new-hashed-secret")
    }

    "update correct client secret where there are multiple" in {
      val applicationId = ApplicationId.random

      val clientSecret1 = ClientSecret("secret-name-1", hashedSecret = "old-hashed-secret-1")
      val clientSecret2 = ClientSecret("secret-name-2", hashedSecret = "old-hashed-secret-2")
      val clientSecret3 = ClientSecret("secret-name-3", hashedSecret = "old-hashed-secret-3")

      await(applicationRepository.save(anApplicationData(applicationId, clientSecrets = List(clientSecret1, clientSecret2, clientSecret3))))

      val updatedApplication = await(applicationRepository.updateClientSecretHash(applicationId, clientSecret2.id, "new-hashed-secret-2"))

      val updatedClientSecrets = updatedApplication.tokens.production.clientSecrets
      updatedClientSecrets.find(_.id == clientSecret2.id).get.hashedSecret should be ("new-hashed-secret-2")

      updatedClientSecrets.find(_.id == clientSecret1.id).get.hashedSecret should be ("old-hashed-secret-1")
      updatedClientSecrets.find(_.id == clientSecret3.id).get.hashedSecret should be ("old-hashed-secret-3")
    }
  }

  "deleteClientSecret" should {
    "remove client secret with matching id" in {
      val applicationId = ApplicationId.random

      val clientSecretToRemove = ClientSecret("secret-name-1", hashedSecret = "old-hashed-secret-1")
      val clientSecret2 = ClientSecret("secret-name-2", hashedSecret = "old-hashed-secret-2")
      val clientSecret3 = ClientSecret("secret-name-3", hashedSecret = "old-hashed-secret-3")

      await(applicationRepository.save(anApplicationData(applicationId, clientSecrets = List(clientSecretToRemove, clientSecret2, clientSecret3))))

      val updatedApplication = await(applicationRepository.deleteClientSecret(applicationId, clientSecretToRemove.id))

      val updatedClientSecrets = updatedApplication.tokens.production.clientSecrets
      updatedClientSecrets.find(_.id == clientSecretToRemove.id) should be (None)
      updatedClientSecrets.find(_.id == clientSecret2.id) should be (Some(clientSecret2))
      updatedClientSecrets.find(_.id == clientSecret3.id) should be (Some(clientSecret3))
    }

  }

  def createAppWithStatusUpdatedOn(state: State.State, updatedOn: DateTime) = anApplicationData(
    id = ApplicationId.random,
    prodClientId = generateClientId,
    state = ApplicationState(state, Some("requestorEmail@example.com"), Some("aVerificationCode"), updatedOn)
  )

  def aSubscriptionData(context: String, version: String, applicationIds: ApplicationId*) = {
    SubscriptionData(context.asIdentifier(version), Set(applicationIds: _*))
  }

  def anApplicationData(id: ApplicationId,
                        prodClientId: ClientId = ClientId("aaa"),
                        state: ApplicationState = testingState(),
                        access: Access = Standard(List.empty, None, None),
                        grantLength: Int = DEFAULT_GRANT_LENGTH,
                        users: Set[Collaborator] = Set(Collaborator("user@example.com", Role.ADMINISTRATOR, UserId.random)),
                        checkInformation: Option[CheckInformation] = None,
                        clientSecrets: List[ClientSecret] = List(ClientSecret("", hashedSecret = "hashed-secret"))): ApplicationData = {

    aNamedApplicationData(id, s"myApp-${id.value}", prodClientId, state, access, users, checkInformation, clientSecrets, grantLength)
  }

  def aNamedApplicationData(id: ApplicationId,
                            name: String,
                            prodClientId: ClientId = ClientId("aaa"),
                            state: ApplicationState = testingState(),
                            access: Access = Standard(List.empty, None, None),
                            users: Set[Collaborator] = Set(Collaborator("user@example.com", Role.ADMINISTRATOR, UserId.random)),
                            checkInformation: Option[CheckInformation] = None,
                            clientSecrets: List[ClientSecret] = List(ClientSecret("", hashedSecret = "hashed-secret")),
                            grantLength: Int = DEFAULT_GRANT_LENGTH): ApplicationData = {

    ApplicationData(
      id,
      name,
      name.toLowerCase,
      users,
      Some("description"),
      "myapplication",
      ApplicationTokens(Token(prodClientId, generateAccessToken, clientSecrets)),
      state,
      access,
      HmrcTime.now,
      Some(HmrcTime.now),
      grantLength = grantLength,
      checkInformation = checkInformation)
  }

}
