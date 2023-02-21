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

import org.mockito.MockitoSugar.{mock, times, verify, verifyNoMoreInteractions}
import org.mongodb.scala.model.{Filters, Updates}
import org.scalatest.BeforeAndAfterEach
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.{StandardAccess => _, _}
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, JavaDateTimeTestUtils, MetricsHelper}
import uk.gov.hmrc.utils.ServerBaseISpec
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax

import java.time.{Clock, Duration, LocalDateTime, ZoneOffset}
import java.util.UUID
import scala.util.Random.nextString
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil

import java.time.temporal.ChronoUnit
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.TermsAndConditionsLocations
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.PrivacyPolicyLocations
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors

class ApplicationRepositoryISpec
    extends ServerBaseISpec
    with SubmissionsTestData
    with ApplicationTestData
    with JavaDateTimeTestUtils
    with ApplicationStateUtil
    with BeforeAndAfterEach
    with MetricsHelper {

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

  private val stateHistoryRepository =
    app.injector.instanceOf[StateHistoryRepository]

  private val notificationRepository =
    app.injector.instanceOf[NotificationRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop.toFuture())
    await(subscriptionRepository.collection.drop.toFuture())
    await(notificationRepository.collection.drop.toFuture())

    await(applicationRepository.ensureIndexes)
    await(subscriptionRepository.ensureIndexes)
    await(notificationRepository.ensureIndexes)
  }

  lazy val defaultGrantLength = 547
  lazy val newGrantLength     = 1000

  private def generateClientId = ClientId.random

  private def generateAccessToken = {
    val lengthOfRandomToken = 5
    nextString(lengthOfRandomToken)
  }

  "save" should {

    "create an application and retrieve it from database" in {
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get

      retrieved mustBe application
    }

    "update an application" in {
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get
      retrieved mustBe application

      val updated = retrieved.copy(name = "new name")
      await(applicationRepository.save(updated))

      val newRetrieved = await(applicationRepository.fetch(application.id)).get
      newRetrieved mustBe updated
    }
  }

  "updateApplicationRateLimit" should {

    "set the rateLimitTier field on an Application document" in {
      val applicationId = ApplicationId.random
      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            ClientId("aaa"),
            productionState("requestorEmail@example.com")
          ).copy(
            rateLimitTier = Some(RateLimitTier.BRONZE),
            lastAccess = Some(FixedClock.now)
          )
        )
      )

      val updatedRateLimit   = RateLimitTier.GOLD
      val updatedApplication = await(
        applicationRepository.updateApplicationRateLimit(
          applicationId,
          updatedRateLimit
        )
      )

      updatedApplication.rateLimitTier mustBe Some(updatedRateLimit)
    }

    "set the grant Length field on an Application document" in {
      val applicationId = ApplicationId.random
      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            ClientId("aaa"),
            grantLength = newGrantLength
          )
        )
      )

      val newRetrieved = await(applicationRepository.fetch(applicationId)).get

      newRetrieved.grantLength mustBe newGrantLength
    }

    "set the rateLimitTier field on an Application document where none previously existed" in {
      val applicationId = ApplicationId.random
      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            ClientId("aaa"),
            productionState("requestorEmail@example.com")
          ).copy(rateLimitTier = None)
        )
      )

      val updatedRateLimit   = RateLimitTier.GOLD
      val updatedApplication = await(
        applicationRepository.updateApplicationRateLimit(
          applicationId,
          updatedRateLimit
        )
      )

      updatedApplication.rateLimitTier mustBe Some(updatedRateLimit)
    }
  }

  "updateApplicationIpAllowlist" should {
    "set the ipAllowlist fields on an Application document" in {
      val applicationId = ApplicationId.random
      await(applicationRepository.save(anApplicationDataForTest(applicationId)))

      val updatedIpAllowlist = IpAllowlist(
        required = true,
        Set("192.168.100.0/22", "192.168.104.1/32")
      )
      val updatedApplication = await(
        applicationRepository.updateApplicationIpAllowlist(
          applicationId,
          updatedIpAllowlist
        )
      )

      updatedApplication.ipAllowlist mustBe updatedIpAllowlist
    }
  }

  "updateApplicationGrantLength" should {
    "set the grantLength fields on an Application document" in {
      val applicationId = ApplicationId.random
      await(applicationRepository.save(anApplicationDataForTest(applicationId)))

      val updatedGrantLength = newGrantLength
      val updatedApplication = await(
        applicationRepository.updateApplicationGrantLength(
          applicationId,
          updatedGrantLength
        )
      )

      updatedApplication.grantLength mustBe updatedGrantLength
    }
  }

  "updateRedirectUris" should {
    "set the redirectUris on an Application document" in {
      val applicationId = ApplicationId.random
      await(applicationRepository.save(anApplicationDataForTest(applicationId)))

      val updateRedirectUris = List("https://new-url.example.com", "https://new-url.example.com/other-redirect")
      val updatedApplication = await(
        applicationRepository.updateRedirectUris(
          applicationId,
          updateRedirectUris
        )
      )

      updatedApplication.access match {
        case access: Standard => access.redirectUris mustBe updateRedirectUris
        case _                => fail("Wrong access - expecting standard")
      }
    }
  }

  "recordApplicationUsage" should {

    "update the lastAccess property" in {
      val applicationId = ApplicationId.random

      val application =
        anApplicationDataForTest(
          applicationId,
          ClientId("aaa"),
          productionState("requestorEmail@example.com")
        )
          .copy(lastAccess =
            Some(FixedClock.now.minusDays(20))
          ) // scalastyle:ignore magic.number

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.recordApplicationUsage(applicationId))

      timestampShouldBeApproximatelyNow(retrieved.lastAccess.get, clock = clock)
    }

    "update the grantLength property" in {
      val applicationId = ApplicationId.random

      val application =
        anApplicationDataForTest(
          applicationId,
          ClientId("aaa"),
          productionState("requestorEmail@example.com"),
          grantLength = newGrantLength
        )
          .copy(lastAccess =
            Some(FixedClock.now.minusDays(20))
          ) // scalastyle:ignore magic.number

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.recordApplicationUsage(applicationId))

      retrieved.grantLength mustBe newGrantLength
    }
  }

  "recordServerTokenUsage" should {
    "update the lastAccess and lastAccessTokenUsage properties" in {
      val applicationId = ApplicationId.random
      val application   =
        anApplicationDataForTest(
          applicationId,
          ClientId("aaa"),
          productionState("requestorEmail@example.com")
        )
          .copy(lastAccess =
            Some(FixedClock.now.minusDays(20))
          ) // scalastyle:ignore magic.number

      application.tokens.production.lastAccessTokenUsage mustBe None

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.recordServerTokenUsage(applicationId))

      timestampShouldBeApproximatelyNow(retrieved.lastAccess.get, clock = clock)
      timestampShouldBeApproximatelyNow(
        retrieved.tokens.production.lastAccessTokenUsage.get,
        clock = clock
      )
    }
  }

  "recordClientSecretUsage" should {
    "create a lastAccess property for client secret if it does not already exist" in {
      val applicationId           = ApplicationId.random
      val application             = anApplicationDataForTest(
        applicationId,
        ClientId("aaa"),
        productionState("requestorEmail@example.com")
      )
      val generatedClientSecretId =
        application.tokens.production.clientSecrets.head.id

      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.recordClientSecretUsage(
          applicationId,
          generatedClientSecretId
        )
      )

      application.tokens.production.clientSecrets.head.lastAccess mustBe None // Original object has no value
      timestampShouldBeApproximatelyNow(
        retrieved.tokens.production.clientSecrets.head.lastAccess.get,
        clock = clock
      )
    }

    "update an existing lastAccess property for a client secret" in {
      val applicationId     = ApplicationId.random
      val applicationTokens = ApplicationTokens(
        Token(
          ClientId("aaa"),
          generateAccessToken,
          List(
            aClientSecret(
              name = "Default",
              lastAccess = Some(FixedClock.now.minusDays(20)),
              hashedSecret = "hashed-secret"
            )
          )
        )
      )

      val application             = anApplicationDataForTest(
        applicationId,
        ClientId("aaa"),
        productionState("requestorEmail@example.com")
      ).copy(tokens = applicationTokens)
      val generatedClientSecretId =
        application.tokens.production.clientSecrets.head.id

      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.recordClientSecretUsage(
          applicationId,
          generatedClientSecretId
        )
      )

      timestampShouldBeApproximatelyNow(
        retrieved.tokens.production.clientSecrets.head.lastAccess.get,
        clock = clock
      )
      retrieved.tokens.production.clientSecrets.head.lastAccess.get.isAfter(
        applicationTokens.production.clientSecrets.head.lastAccess.get
      ) mustBe true
    }

    "update the correct client secret when there are multiple" in {
      val testStartTime     = FixedClock.now
      val applicationId     = ApplicationId.random
      val secretToUpdate    =
        aClientSecret(
          name = "SecretToUpdate",
          lastAccess = Some(FixedClock.now.minusDays(20)),
          hashedSecret = "hashed-secret"
        )
      val applicationTokens =
        ApplicationTokens(
          Token(
            ClientId("aaa"),
            generateAccessToken,
            List(
              secretToUpdate,
              aClientSecret(
                name = "SecretToLeave",
                lastAccess = Some(FixedClock.now.minusDays(20)),
                hashedSecret = "hashed-secret"
              )
            )
          )
        )
      val application       = anApplicationDataForTest(
        applicationId,
        ClientId("aaa"),
        productionState("requestorEmail@example.com")
      ).copy(tokens = applicationTokens)

      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.recordClientSecretUsage(
          applicationId,
          secretToUpdate.id
        )
      )

      retrieved.tokens.production.clientSecrets.foreach(retrievedClientSecret =>
        if (retrievedClientSecret.id == secretToUpdate.id)
          timestampShouldBeApproximatelyNow(
            retrievedClientSecret.lastAccess.get,
            clock = clock
          )
        else
          retrievedClientSecret.lastAccess.get.isBefore(
            testStartTime
          ) mustBe true
      )
    }
  }

  "fetchByClientId" should {

    "retrieve the application for a given client id when it has a matching client id" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa"),
        productionState("requestorEmail@example.com")
      )
      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("zzz"),
        productionState("requestorEmail@example.com")
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(
        applicationRepository.fetchByClientId(
          application2.tokens.production.clientId
        )
      )

      retrieved mustBe Some(application2)
    }

    "retrieve the grant length for an application for a given client id when it has a matching client id" in {
      val grantLength1 = 510
      val grantLength2 = 1000
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa"),
        productionState("requestorEmail@example.com"),
        access = Standard(),
        grantLength1
      )
      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("zzz"),
        productionState("requestorEmail@example.com"),
        access = Standard(),
        grantLength2
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved1 = await(
        applicationRepository.fetchByClientId(
          application1.tokens.production.clientId
        )
      )
      val retrieved2 = await(
        applicationRepository.fetchByClientId(
          application2.tokens.production.clientId
        )
      )

      retrieved1.map(_.grantLength) mustBe Some(grantLength1)
      retrieved2.map(_.grantLength) mustBe Some(grantLength2)
    }

    "do not retrieve the application for a given client id when it has a matching client id but is deleted" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa"),
        deletedState("requestorEmail@example.com")
      )

      await(applicationRepository.save(application1))

      val retrieved = await(
        applicationRepository.fetchByClientId(
          application1.tokens.production.clientId
        )
      )

      retrieved mustBe None
    }
  }

  "fetchByServerToken" should {

    "retrieve the application when it is matched for access token" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa"),
        productionState("requestorEmail@example.com")
      )
      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("zzz"),
        productionState("requestorEmail@example.com")
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(
        applicationRepository.fetchByServerToken(
          application2.tokens.production.accessToken
        )
      )

      retrieved mustBe Some(application2)
    }

    "do not retrieve the application when it is matched for access token but is deleted" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa"),
        deletedState("requestorEmail@example.com")
      )

      await(applicationRepository.save(application1))

      val retrieved = await(
        applicationRepository.fetchByServerToken(
          application1.tokens.production.accessToken
        )
      )

      retrieved mustBe None
    }
  }

  "fetchAllForEmailAddress" should {
    "retrieve all the applications for a given collaborator email address" in {
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
        prodClientId = generateClientId,
        deletedState("requestorEmail@example.com")
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      val retrieved =
        await(applicationRepository.fetchAllForEmailAddress("user@example.com"))

      retrieved mustBe List(application1, application2)
    }
  }

  "fetchStandardNonTestingApps" should {
    "retrieve all the standard applications not in TESTING (or DELETED) state" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId,
        state = pendingRequesterVerificationState("user1")
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId,
        state = productionState("user2")
      )
      val application4 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId,
        state = pendingRequesterVerificationState("user2")
      )
      val application5 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId,
        state = deletedState("user2")
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      val retrieved = await(applicationRepository.fetchStandardNonTestingApps())

      retrieved.toSet mustBe Set(application2, application3, application4)
    }

    "return empty list when no apps are found" in {
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "not return Privileged applications" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        state = productionState("gatekeeper"),
        access = Privileged()
      )
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "not return ROPC applications" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        state = productionState("gatekeeper"),
        access = Ropc()
      )
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "return empty list when all apps in TESTING state" in {
      val application1 = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "return empty list when all apps in DELETED state" in {
      val application1 = anApplicationDataForTest(ApplicationId.random, state = deletedState("user2"))
      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }
  }

  "fetchNonTestingApplicationByName" should {

    "retrieve the application with the matching name" in {
      val applicationName           = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.fetchApplicationsByName(applicationName))

      retrieved mustBe List(application)
    }

    "dont retrieve the application if it's a non-matching name" in {
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)
      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.fetchApplicationsByName("non-matching-name")
      )

      retrieved mustBe List.empty
    }

    "dont retrieve the application with the matching name if its deleted" in {
      val applicationName           = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random, state = deletedState("user2"))
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.fetchApplicationsByName(applicationName))

      retrieved mustBe List.empty
    }
  }

  "fetchAllByStatusDetails" should {

    val dayOfExpiry          = FixedClock.now
    val expiryOnTheDayBefore = dayOfExpiry.minusDays(1)
    val expiryOnTheDayAfter  = dayOfExpiry.plusDays(1)

    def verifyApplications(
        responseApplications: Seq[ApplicationData],
        expectedState: State.State,
        expectedNumber: Int
      ): Unit = {
      responseApplications.foreach(app => app.state.name mustBe expectedState)
      withClue(
        s"The expected number of applications with state $expectedState is $expectedNumber"
      ) {
        responseApplications.size mustBe expectedNumber
      }
    }

    "retrieve the only application with PENDING_REQUESTER_VERIFICATION state that have been updated before the expiryDay" in {
      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, expiryOnTheDayBefore),
        createAppWithStatusUpdatedOn(
          State.PENDING_GATEKEEPER_APPROVAL,
          expiryOnTheDayBefore
        ),
        createAppWithStatusUpdatedOn(
          State.PENDING_REQUESTER_VERIFICATION,
          expiryOnTheDayBefore
        ),
        createAppWithStatusUpdatedOn(State.PRODUCTION, expiryOnTheDayBefore)
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )

      val applicationDetails = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetails,
        State.PENDING_REQUESTER_VERIFICATION,
        1
      )
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated before the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(
        State.PENDING_REQUESTER_VERIFICATION,
        expiryOnTheDayBefore
      )
      await(applicationRepository.save(application))

      val applicationDetails = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetails,
        State.PENDING_REQUESTER_VERIFICATION,
        1
      )
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated on the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(
        State.PENDING_REQUESTER_VERIFICATION,
        dayOfExpiry
      )
      await(applicationRepository.save(application))

      val applicationDetails = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetails,
        State.PENDING_REQUESTER_VERIFICATION,
        1
      )
    }

    "retrieve no application with PENDING_REQUESTER_VERIFICATION state that have been updated after the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(
        State.PENDING_REQUESTER_VERIFICATION,
        expiryOnTheDayAfter
      )
      await(applicationRepository.save(application))

      val applicationDetail = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetail,
        State.PENDING_REQUESTER_VERIFICATION,
        0
      )
    }
  }

  "fetchByStatusDetailsAndEnvironment" should {

    val currentDate        = FixedClock.now
    val yesterday          = currentDate.minusDays(1)
    val dayBeforeYesterday = currentDate.minusDays(2)
    val lastWeek           = currentDate.minusDays(7)

    def verifyApplications(
        responseApplications: Seq[ApplicationData],
        expectedState: State.State,
        expectedNumber: Int
      ): Unit = {
      responseApplications.foreach(app => app.state.name mustBe expectedState)
      withClue(
        s"The expected number of applications with state $expectedState is $expectedNumber"
      ) {
        responseApplications.size mustBe expectedNumber
      }
    }

    "retrieve the only application with TESTING state that have been updated before the expiryDay" in {
      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, currentDate),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, lastWeek)
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )

      val applicationDetails = await(
        applicationRepository.fetchByStatusDetailsAndEnvironment(
          State.TESTING,
          yesterday,
          Environment.PRODUCTION
        )
      )

      verifyApplications(
        applicationDetails,
        State.TESTING,
        2
      )
    }
  }

  "fetchByStatusDetailsAndEnvironmentNotAleadyNotified" should {

    val currentDate        = FixedClock.now
    val yesterday          = currentDate.minusDays(1)
    val dayBeforeYesterday = currentDate.minusDays(2)
    val lastWeek           = currentDate.minusDays(7)

    def verifyApplications(
        responseApplications: Seq[ApplicationData],
        expectedState: State.State,
        expectedNumber: Int
      ): Unit = {
      responseApplications.foreach(app => app.state.name mustBe expectedState)
      withClue(
        s"The expected number of applications with state $expectedState is $expectedNumber"
      ) {
        responseApplications.size mustBe expectedNumber
      }
    }

    "retrieve the only application with TESTING state that have been updated before the expiryDay" in {
      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, currentDate),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, lastWeek)
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )

      val applicationDetails = await(
        applicationRepository.fetchByStatusDetailsAndEnvironmentNotAleadyNotified(
          State.TESTING,
          yesterday,
          Environment.PRODUCTION
        )
      )

      verifyApplications(
        applicationDetails,
        State.TESTING,
        2
      )
    }

    "retrieve the only application with TESTING state that have been updated before the expiryDay and don't return already notified ones" in {
      val app4 = createAppWithStatusUpdatedOn(State.TESTING, lastWeek)

      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, currentDate),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday),
        app4
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )
      await(notificationRepository.createEntity(Notification(app4.id, lastWeek, NotificationType.PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING, NotificationStatus.SENT)))

      val applicationDetails = await(
        applicationRepository.fetchByStatusDetailsAndEnvironmentNotAleadyNotified(
          State.TESTING,
          yesterday,
          Environment.PRODUCTION
        )
      )

      verifyApplications(
        applicationDetails,
        State.TESTING,
        1
      )
    }
  }

  "fetchVerifiableBy" should {

    "retrieve the application with verificationCode when in pendingRequesterVerification state" in {
      val application = anApplicationDataForTest(
        ApplicationId.random,
        state = pendingRequesterVerificationState("requestorEmail@example.com")
      )
      await(applicationRepository.save(application))
      val retrieved   = await(
        applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)
      )
      retrieved mustBe Some(application)
    }

    "retrieve the application with verificationCode when in production state" in {
      val application = anApplicationDataForTest(
        ApplicationId.random,
        state = productionState("requestorEmail@example.com")
      )
      await(applicationRepository.save(application))
      val retrieved   = await(
        applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)
      )
      retrieved mustBe Some(application)
    }

    "not retrieve the application with an unknown verificationCode" in {
      val application = anApplicationDataForTest(
        ApplicationId.random,
        state = pendingRequesterVerificationState("requestorEmail@example.com")
      )
      await(applicationRepository.save(application))
      val retrieved   = await(
        applicationRepository.fetchVerifiableUpliftBy(
          "aDifferentVerificationCode"
        )
      )
      retrieved mustBe None
    }

    "not retrieve the application with verificationCode when in deleted state" in {
      val application = anApplicationDataForTest(
        ApplicationId.random,
        state = pendingRequesterVerificationState("requestorEmail@example.com")
      )
      await(applicationRepository.save(application))
      await(applicationRepository.delete(application.id, FixedClock.now))

      val retrieved = await(
        applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode)
      )
      retrieved mustBe None
    }
  }

  "delete" should {

    "change an application's state to Deleted" in {
      val now         = FixedClock.now
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get
      retrieved mustBe application

      await(applicationRepository.delete(application.id, now))
      val result = await(applicationRepository.fetch(application.id))

      result.isDefined mustBe true
      result.get.state.name mustBe State.DELETED
    }
  }

  "hardDelete" should {

    "delete an application from the database" in {
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get
      retrieved mustBe application

      await(applicationRepository.hardDelete(application.id))
      val result = await(applicationRepository.fetch(application.id))

      result mustBe None
    }
  }

  "fetch" should {

    // API-3862: The wso2Username and wso2Password fields have been removed from ApplicationData, but will still exist in Mongo for most applications
    // Test that documents are still correctly deserialised into ApplicationData objects
    "retrieve an application when wso2Username and wso2Password exist" in {
      val applicationId = ApplicationId.random
      val application   = anApplicationDataForTest(applicationId)

      await(applicationRepository.save(application))
      await(
        applicationRepository.collection
          .findOneAndUpdate(
            Filters.equal("id", Codecs.toBson(applicationId)),
            Updates.set("wso2Username", "legacyUsername")
          )
          .toFuture()
      )
      await(
        applicationRepository.collection
          .findOneAndUpdate(
            Filters.equal("id", Codecs.toBson(applicationId)),
            Updates.set("wso2Password", "legacyPassword")
          )
          .toFuture()
      )

      val result = await(applicationRepository.fetch(applicationId))

      result must not be None
    }
  }

  "fetchAllWithNoSubscriptions" should {
    "fetch only those applications with no subscriptions" in { // Needs revisiting

      val application1     = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2     = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val subscriptionData =
        aSubscriptionData("context", "version", application1.id)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(
        subscriptionRepository.collection.insertOne(subscriptionData).toFuture()
      )

      val result = await(applicationRepository.fetchAllWithNoSubscriptions())

      result mustBe List(application2)
    }
  }

  "fetchAll" should {

    "fetch all existing applications" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(applicationRepository.fetchAll()) mustBe List(
        application1,
        application2
      )
    }
  }

  "fetchAllForContext" should {

    "fetch only those applications when the context matches" in {
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

      val result =
        await(applicationRepository.fetchAllForContext("context".asContext))

      result mustBe List(application1, application2)
    }
  }

  "fetchAllForApiIdentifier" should {

    "fetch only those applications when the context and version matches" in {
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

      val result = await(
        applicationRepository.fetchAllForApiIdentifier(
          "context".asIdentifier("version-2")
        )
      )

      result mustBe List(application2)
    }

    "fetch multiple applications with the same matching context and versions" in {
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

      val result = await(
        applicationRepository.fetchAllForApiIdentifier(
          "context".asIdentifier("version-2")
        )
      )

      result mustBe List(application2, application3)
    }

    "fetch no applications when the context and version do not match" in {
      val application1                            = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2                            = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
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

      val result = await(
        applicationRepository.fetchAllForApiIdentifier(nonExistentApiIdentifier)
      )

      result mustBe List.empty
    }
  }

  "Search" should {
    def applicationWithLastAccessDate(
        applicationId: ApplicationId,
        lastAccessDate: LocalDateTime
      ): ApplicationData =
      anApplicationDataForTest(
        id = applicationId,
        prodClientId = generateClientId
      ).copy(lastAccess = Some(lastAccessDate))

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
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 3
      result.matching.size mustBe 1
      result.matching.head.total mustBe 3
      result.applications.size mustBe 1                  // as a result of pageSize = 1
      result.applications.head.id mustBe application2.id // as a result of pageNumber = 2
    }

    "return applications based on application state filter Active" in {
      val applicationInTest       = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val applicationInProduction =
        createAppWithStatusUpdatedOn(State.PRODUCTION, FixedClock.now)
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationInProduction))

      val applicationSearch = new ApplicationSearch(filters = List(Active))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

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
        createAppWithStatusUpdatedOn(State.DELETED, FixedClock.now)
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationDeleted))

      val applicationSearch = new ApplicationSearch(filters = List(WasDeleted), includeDeleted = true)

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

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
        createAppWithStatusUpdatedOn(State.DELETED, FixedClock.now)
      await(applicationRepository.save(applicationInTest))
      await(applicationRepository.save(applicationDeleted))

      val applicationSearch = new ApplicationSearch(filters = List(ExcludingDeleted), includeDeleted = true)

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

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
        prodClientId = generateClientId,
        access = Ropc()
      )
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(filters = List(ROPCAccess))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe ropcApplication.id
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
        await(applicationRepository.searchApplications(applicationSearch))

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
        await(applicationRepository.searchApplications(applicationSearch))
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
        applicationName,
        prodClientId = generateClientId
      )
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
        await(applicationRepository.searchApplications(applicationSearch))

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
        applicationName,
        prodClientId = generateClientId
      )
      val randomOtherApplication   = anApplicationDataForTest(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      val randomDeletedApplication = aNamedApplicationData(
        ApplicationId.random,
        applicationName,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(randomDeletedApplication))
      await(applicationRepository.delete(randomDeletedApplication.id, FixedClock.now))
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ApplicationTextSearch),
        textToSearch = Some(applicationName)
      )

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

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
        applicationName,
        prodClientId = generateClientId
      )
      val randomOtherApplication   = anApplicationDataForTest(
        ApplicationId.random,
        prodClientId = generateClientId
      )
      val randomDeletedApplication = aNamedApplicationData(
        ApplicationId.random,
        applicationName,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(randomDeletedApplication))
      await(applicationRepository.delete(randomDeletedApplication.id, FixedClock.now))
      await(applicationRepository.save(application))
      await(applicationRepository.save(randomOtherApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ApplicationTextSearch),
        textToSearch = Some(applicationName),
        includeDeleted = true
      )

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

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
        applicationName,
        prodClientId = clientId
      )
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
        await(applicationRepository.searchApplications(applicationSearch))

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
          applicationName,
          prodClientId = generateClientId
        )
      val ropcApplication     =
        aNamedApplicationData(
          id = ApplicationId.random,
          applicationName,
          prodClientId = generateClientId,
          access = Ropc()
        )
      await(applicationRepository.save(standardApplication))
      await(applicationRepository.save(ropcApplication))

      val applicationSearch = new ApplicationSearch(
        filters = List(ROPCAccess),
        textToSearch = Some(applicationName)
      )

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      // Only ROPC application should be returned
      result.applications.size mustBe 1
      result.applications.head.id mustBe ropcApplication.id
    }

    "return applications matching search text in a case-insensitive manner" in {
      val applicationId = ApplicationId.random

      val application            = aNamedApplicationData(
        applicationId,
        "TEST APPLICATION",
        prodClientId = generateClientId
      )
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
        await(applicationRepository.searchApplications(applicationSearch))

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
        await(applicationRepository.searchApplications(applicationSearch))

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
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe expectedApplication.id
    }

    "return applications last used before a certain date" in {
      val oldApplicationId = ApplicationId.random
      val cutoffDate       = FixedClock.now.minusMonths(12)

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            oldApplicationId,
            FixedClock.now.minusMonths(18)
          )
        )
      )
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            FixedClock.now
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

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
        createdOn = FixedClock.now.minusMonths(18),
        lastAccess = None
      )
      val cutoffDate       = FixedClock.now.minusMonths(12)

      await(applicationRepository.save(oldApplication))
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            FixedClock.now
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe oldApplicationId
    }

    "return applications that are equal to the specified cutoff date when searching for older applications" in {
      val oldApplicationId = ApplicationId.random
      val cutoffDate       = FixedClock.now.minusMonths(12)

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(oldApplicationId, cutoffDate)
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 1
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe oldApplicationId
    }

    "return no results if no applications are last used before the cutoff date" in {
      val cutoffDate = FixedClock.now.minusMonths(12)
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            FixedClock.now
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseBeforeDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.applications.size mustBe 0
    }

    "return applications last used after a certain date" in {
      val newerApplicationId = ApplicationId.random
      val cutoffDate         = FixedClock.now.minusMonths(12)

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            newerApplicationId,
            FixedClock.now
          )
        )
      )
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            FixedClock.now.minusMonths(18)
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

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
      val cutoffDate         = FixedClock.now.minusMonths(12)

      await(applicationRepository.save(newerApplication))
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            FixedClock.now.minusMonths(18)
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe newerApplicationId
    }

    "return applications that are equal to the specified cutoff date when searching for newer applications" in {
      val applicationId = ApplicationId.random
      val cutoffDate    = FixedClock.now.minusMonths(12)

      await(
        applicationRepository.save(
          applicationWithLastAccessDate(applicationId, cutoffDate)
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 1
      result.matching.size mustBe 1
      result.matching.head.total mustBe 1
      result.applications.size mustBe 1
      result.applications.head.id mustBe applicationId
    }

    "return no results if no applications are last used after the cutoff date" in {
      val cutoffDate = FixedClock.now
      await(
        applicationRepository.save(
          applicationWithLastAccessDate(
            ApplicationId.random,
            FixedClock.now.minusMonths(6)
          )
        )
      )

      val applicationSearch =
        new ApplicationSearch(filters = List(LastUseAfterDate(cutoffDate)))

      val result =
        await(applicationRepository.searchApplications(applicationSearch))

      result.applications.size mustBe 0
    }

    "return applications sorted by name ascending" in {
      val firstName         = "AAA first"
      val secondName        = "ZZZ second"
      val firstApplication  =
        aNamedApplicationData(
          id = ApplicationId.random,
          name = firstName,
          prodClientId = generateClientId
        )
      val secondApplication =
        aNamedApplicationData(
          id = ApplicationId.random,
          name = secondName,
          prodClientId = generateClientId
        )

      await(applicationRepository.save(secondApplication))
      await(applicationRepository.save(firstApplication))

      val applicationSearch = new ApplicationSearch(sort = NameAscending)
      val result            =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.name mustBe firstName
      result.applications.last.name mustBe secondName
    }

    "return applications sorted by name descending" in {
      val firstName         = "AAA first"
      val secondName        = "ZZZ second"
      val firstApplication  =
        aNamedApplicationData(
          id = ApplicationId.random,
          name = firstName,
          prodClientId = generateClientId
        )
      val secondApplication =
        aNamedApplicationData(
          id = ApplicationId.random,
          name = secondName,
          prodClientId = generateClientId
        )

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val applicationSearch = new ApplicationSearch(sort = NameDescending)
      val result            =
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.name mustBe secondName
      result.applications.last.name mustBe firstName
    }

    "return applications sorted by submitted ascending" in {
      val firstCreatedOn    = FixedClock.now.minusDays(2)
      val secondCreatedOn   = FixedClock.now.minusDays(1)
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
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.createdOn mustBe firstCreatedOn
      result.applications.last.createdOn mustBe secondCreatedOn
    }

    "return applications sorted by submitted descending" in {
      val firstCreatedOn    = FixedClock.now.minusDays(2)
      val secondCreatedOn   = FixedClock.now.minusDays(1)
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
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.createdOn mustBe secondCreatedOn
      result.applications.last.createdOn mustBe firstCreatedOn
    }

    "return applications sorted by lastAccess ascending" in {
      val mostRecentlyAccessedDate = FixedClock.now.minusDays(1)
      val oldestLastAccessDate     = FixedClock.now.minusDays(2)
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
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.lastAccess mustBe Some(oldestLastAccessDate)
      result.applications.last.lastAccess mustBe Some(mostRecentlyAccessedDate)
    }

    "return applications sorted by lastAccess descending" in {
      val mostRecentlyAccessedDate = FixedClock.now.minusDays(1)
      val oldestLastAccessDate     = FixedClock.now.minusDays(2)
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
        await(applicationRepository.searchApplications(applicationSearch))

      result.totals.size mustBe 1
      result.totals.head.total mustBe 2
      result.matching.size mustBe 1
      result.matching.head.total mustBe 2
      result.applications.size mustBe 2
      result.applications.head.lastAccess mustBe Some(mostRecentlyAccessedDate)
      result.applications.last.lastAccess mustBe Some(oldestLastAccessDate)
    }
  }

  "processAll" should {
    class TestService {
      def doSomething(application: ApplicationData): ApplicationData =
        application
    }

    "ensure function is called for every Application in collection" in {
      val firstApplication  = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val secondApplication = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val mockTestService = mock[TestService]

      await(
        applicationRepository.processAll(a => mockTestService.doSomething(a))
      )

      verify(mockTestService, times(1)).doSomething(firstApplication)
      verify(mockTestService, times(1)).doSomething(secondApplication)
      verifyNoMoreInteractions(mockTestService)
    }
  }

  "ApplicationWithSubscriptionCount" should {
    "return Applications with a count of subscriptions" in {
      val api1        = "api-1"
      val api2        = "api-2"
      val api3        = "api-3"
      val api1Version = "api-1-version-1"
      val api2Version = "api-2-version-2"
      val api3Version = "api-3-version-3"

      val application1 = aNamedApplicationData(
        id = ApplicationId.random,
        name = "organisations/trusts",
        prodClientId = generateClientId
      )
      val application2 = aNamedApplicationData(
        id = ApplicationId.random,
        name = "application.com",
        prodClientId = generateClientId
      )
      val application3 = aNamedApplicationData(
        id = ApplicationId.random,
        name = "Get) Vat Done (Fast)",
        prodClientId = generateClientId
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api1, api1Version, application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api2, api2Version, application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api3, api3Version, application2.id))
          .toFuture()
      )

      val sanitisedApp1Name = sanitiseGrafanaNodeName(application1.name)
      val sanitisedApp2Name = sanitiseGrafanaNodeName(application2.name)
      val sanitisedApp3Name = sanitiseGrafanaNodeName(application3.name)

      val result =
        await(applicationRepository.getApplicationWithSubscriptionCount())

      result.get(
        s"applicationsWithSubscriptionCountV1.${sanitisedApp1Name}"
      ) mustBe Some(2)
      result.get(
        s"applicationsWithSubscriptionCountV1.${sanitisedApp2Name}"
      ) mustBe Some(1)
      result.get(
        s"applicationsWithSubscriptionCountV1.${sanitisedApp3Name}"
      ) mustBe None
    }

    "return Applications when more than 100 results bug" in {
      (1 to 200).foreach(i => {
        val api        = s"api-$i"
        val apiVersion = s"api-$i-version-$i"

        val application = anApplicationDataForTest(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        await(applicationRepository.save(application))

        await(
          subscriptionRepository.collection
            .insertOne(aSubscriptionData(api, apiVersion, application.id))
            .toFuture()
        )
      })

      val result =
        await(applicationRepository.getApplicationWithSubscriptionCount())

      result.keys.count(_ => true) mustBe 200
    }
  }

  "addClientSecret" should {
    "append client secrets to an existing application" in {
      val applicationId = ApplicationId.random

      val savedApplication = await(
        applicationRepository.save(anApplicationDataForTest(applicationId))
      )

      val clientSecret       =
        aClientSecret(name = "secret-name", hashedSecret = "hashed-secret")
      val updatedApplication = await(
        applicationRepository.addClientSecret(applicationId, clientSecret)
      )

      savedApplication.tokens.production.clientSecrets must not contain clientSecret
      updatedApplication.tokens.production.clientSecrets must contain(
        clientSecret
      )
    }
  }

  "updateClientSecretName" should {
    def clientSecretWithId(
        application: ApplicationData,
        clientSecretId: String
      ): ClientSecret =
      application.tokens.production.clientSecrets
        .find(_.id == clientSecretId)
        .get
    def otherClientSecrets(
        application: ApplicationData,
        clientSecretId: String
      ): Seq[ClientSecret] =
      application.tokens.production.clientSecrets
        .filterNot(_.id == clientSecretId)

    "populate the name where it was an empty String" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(aClientSecret(clientSecretId))
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
    }

    "populate the name where it was Default" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(aClientSecret(clientSecretId, name = "Default"))
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
    }

    "populate the name where it was a masked String" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(
              aClientSecret(
                clientSecretId,
                name = "••••••••••••••••••••••••••••••••abc1"
              )
            )
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
    }

    "update correct client secret where there are multiple" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = UUID.randomUUID().toString

      val clientSecret1 = aClientSecret(name = "secret-that-should-not-change")
      val clientSecret2 = aClientSecret(name = "secret-that-should-not-change")
      val clientSecret3 = aClientSecret(clientSecretId, name = "secret-3")

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(clientSecret1, clientSecret2, clientSecret3)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
      otherClientSecrets(updatedApplication, clientSecretId) foreach {
        otherSecret =>
          otherSecret.name mustBe ("secret-that-should-not-change")
      }
    }
  }

  "addApplicationTermsOfUseAcceptance" should {
    "update the application correctly" in {
      val responsibleIndividual   = ResponsibleIndividual(
        ResponsibleIndividual.Name("bob"),
        LaxEmailAddress("bob@example.com")
      )
      val acceptanceDate          = FixedClock.now
      val submissionId            = SubmissionId.random
      val acceptance              = TermsOfUseAcceptance(
        responsibleIndividual,
        acceptanceDate,
        submissionId,
        0
      )
      val applicationId           = ApplicationId.random
      val importantSubmissionData = ImportantSubmissionData(
        None,
        responsibleIndividual,
        Set.empty,
        TermsAndConditionsLocations.InDesktopSoftware,
        PrivacyPolicyLocations.InDesktopSoftware,
        termsOfUseAcceptances = List()
      )
      val application             = anApplicationDataForTest(applicationId).copy(access =
        Standard(importantSubmissionData = Some(importantSubmissionData))
      )
      await(applicationRepository.save(application))
      val updatedApplication      = await(
        applicationRepository.addApplicationTermsOfUseAcceptance(
          applicationId,
          acceptance
        )
      )

      val termsOfUseAcceptances = updatedApplication.access
        .asInstanceOf[Standard]
        .importantSubmissionData
        .get
        .termsOfUseAcceptances
      termsOfUseAcceptances.size mustBe 1

      val termsOfUseAcceptance = termsOfUseAcceptances.head
      termsOfUseAcceptance.responsibleIndividual mustBe responsibleIndividual
      termsOfUseAcceptance.dateTime
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli mustBe acceptanceDate
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli
      termsOfUseAcceptance.submissionId mustBe submissionId
      termsOfUseAcceptance.submissionInstance mustBe 0
    }
  }

  "updateClientSecretHash" should {
    "overwrite an existing hashedSecretField" in {
      val applicationId = ApplicationId.random
      val clientSecret  =
        aClientSecret(name = "secret-name", hashedSecret = "old-hashed-secret")

      val savedApplication = await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(clientSecret)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretHash(
          applicationId,
          clientSecret.id,
          "new-hashed-secret"
        )
      )

      savedApplication.tokens.production.clientSecrets.head.hashedSecret must be(
        "old-hashed-secret"
      )
      updatedApplication.tokens.production.clientSecrets.head.hashedSecret must be(
        "new-hashed-secret"
      )
    }

    "update correct client secret where there are multiple" in {
      val applicationId = ApplicationId.random

      val clientSecret1 = aClientSecret(name = "secret-name-1", hashedSecret = "old-hashed-secret-1")
      val clientSecret2 = aClientSecret(name = "secret-name-2", hashedSecret = "old-hashed-secret-2")
      val clientSecret3 = aClientSecret(name = "secret-name-3", hashedSecret = "old-hashed-secret-3")

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(clientSecret1, clientSecret2, clientSecret3)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretHash(
          applicationId,
          clientSecret2.id,
          "new-hashed-secret-2"
        )
      )

      val updatedClientSecrets =
        updatedApplication.tokens.production.clientSecrets
      updatedClientSecrets
        .find(_.id == clientSecret2.id)
        .get
        .hashedSecret must be("new-hashed-secret-2")

      updatedClientSecrets
        .find(_.id == clientSecret1.id)
        .get
        .hashedSecret must be("old-hashed-secret-1")
      updatedClientSecrets
        .find(_.id == clientSecret3.id)
        .get
        .hashedSecret must be("old-hashed-secret-3")
    }
  }

  "deleteClientSecret" should {
    "remove client secret with matching id" in {
      val applicationId = ApplicationId.random

      val clientSecretToRemove = aClientSecret(name = "secret-name-1", hashedSecret = "old-hashed-secret-1")
      val clientSecret2        = aClientSecret(name = "secret-name-2", hashedSecret = "old-hashed-secret-2")
      val clientSecret3        = aClientSecret(name = "secret-name-3", hashedSecret = "old-hashed-secret-3")

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets =
              List(clientSecretToRemove, clientSecret2, clientSecret3)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.deleteClientSecret(
          applicationId,
          clientSecretToRemove.id
        )
      )

      val updatedClientSecrets =
        updatedApplication.tokens.production.clientSecrets
      updatedClientSecrets.find(_.id == clientSecret2.id) mustBe (Some(
        clientSecret2
      ))
      updatedClientSecrets.find(_.id == clientSecretToRemove.id) mustBe (None)
      updatedClientSecrets.find(_.id == clientSecret3.id) mustBe (Some(
        clientSecret3
      ))
    }
  }

  "fetchAllForUserId" should {
    "return two applications when all have the same userId" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random

      val collaborator     = "user@example.com".admin(userId)
      val testApplication1 = anApplicationDataForTest(applicationId1)
        .copy(collaborators = Set(collaborator))
      val testApplication2 =
        anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb"))
          .copy(collaborators = Set(collaborator))
      val testApplication3 =
        anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc"), state = deletedState("user1"))
          .copy(collaborators = Set(collaborator))

      await(applicationRepository.save(testApplication1))
      await(applicationRepository.save(testApplication2))
      await(applicationRepository.save(testApplication3))

      val result = await(applicationRepository.fetchAllForUserId(userId, false))

      result.size mustBe 2
      result.map(
        _.collaborators.map(collaborator => collaborator.userId mustBe userId)
      )
    }

    "return three applications when all have the same userId and one is deleted" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random

      val collaborator     = "user@example.com".admin(userId)
      val testApplication1 = anApplicationDataForTest(applicationId1)
        .copy(collaborators = Set(collaborator))
      val testApplication2 =
        anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb"))
          .copy(collaborators = Set(collaborator))
      val testApplication3 =
        anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc"), state = deletedState("user1"))
          .copy(collaborators = Set(collaborator))

      await(applicationRepository.save(testApplication1))
      await(applicationRepository.save(testApplication2))
      await(applicationRepository.save(testApplication3))

      val result = await(applicationRepository.fetchAllForUserId(userId, true))

      result.size mustBe 3
      result.map(
        _.collaborators.map(collaborator => collaborator.userId mustBe userId)
      )
    }
  }

  "fetchAllForUserIdAndEnvironment" should {
    "return one application when 3 apps have the same userId but only one is in Production and not deleted" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random
      val productionEnv  = Environment.PRODUCTION.toString

      val collaborator = "user@example.com".admin(userId)

      val prodApplication1   = anApplicationDataForTest(applicationId1)
        .copy(environment = productionEnv, collaborators = Set(collaborator))
      val prodApplication2   = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb"), state = deletedState("user2"))
        .copy(environment = productionEnv, collaborators = Set(collaborator))
      val sandboxApplication =
        anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc"))
          .copy(
            environment = Environment.SANDBOX.toString,
            collaborators = Set(collaborator)
          )

      await(applicationRepository.save(prodApplication1))
      await(applicationRepository.save(prodApplication2))
      await(applicationRepository.save(sandboxApplication))

      val result = await(
        applicationRepository.fetchAllForUserIdAndEnvironment(
          userId,
          productionEnv
        )
      )

      result.size mustBe 1
      result.head.environment mustBe productionEnv
      result.map(
        _.collaborators.map(collaborator => collaborator.userId mustBe userId)
      )
    }
  }

  "fetchAllForEmailAddressAndEnvironment" should {
    "return one application when 3 apps have the same user email but only one is in Production and not deleted" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random
      val productionEnv  = Environment.PRODUCTION.toString

      val collaborator = "user@example.com".admin(userId)

      val prodApplication1   = anApplicationDataForTest(applicationId1)
        .copy(environment = productionEnv, collaborators = Set(collaborator))
      val prodApplication2   = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb"), state = deletedState("user"))
        .copy(environment = productionEnv, collaborators = Set(collaborator))
      val sandboxApplication =
        anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc"))
          .copy(
            environment = Environment.SANDBOX.toString,
            collaborators = Set(collaborator)
          )

      await(applicationRepository.save(prodApplication1))
      await(applicationRepository.save(prodApplication2))
      await(applicationRepository.save(sandboxApplication))

      val result = await(
        applicationRepository.fetchAllForEmailAddressAndEnvironment(
          collaborator.emailAddress.text,
          productionEnv
        )
      )

      result.size mustBe 1
      result.head.environment mustBe productionEnv
      result.map(
        _.collaborators.map(x =>
          x.emailAddress mustBe collaborator.emailAddress
        )
      )
    }
  }

  "documentsWithFieldMissing" should {
    "return count of documents with missing description" in {
      val appWithNoDescription = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .copy(description = None)
      val appWithDescription   = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .copy(description = Some("A description"))

      await(applicationRepository.save(appWithNoDescription))
      await(applicationRepository.save(appWithDescription))

      val numberRetrieved =
        await(applicationRepository.documentsWithFieldMissing("description"))

      numberRetrieved mustBe 1
    }
  }

  "count" should {
    "return count of documents in the collection" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val numberRetrieved = await(applicationRepository.count)

      numberRetrieved mustBe 2
    }
  }

  "handle addCollaborator correctly" in {
    val applicationId = ApplicationId.random

    val app = anApplicationData(applicationId)
    await(applicationRepository.save(app))

    val collaborator          = "email".developer()
    val existingCollaborators = app.collaborators

    val appWithNewCollaborator = await(applicationRepository.addCollaborator(applicationId, collaborator))
    appWithNewCollaborator.collaborators must contain only (existingCollaborators.toList ++ List(collaborator): _*)
  }

  "handle removeCollaborator correctly" in {
    val applicationId = ApplicationId.random

    val developerCollaborator = "email".developer()
    val adminCollaborator     = "email2".admin()
    val app                   = anApplicationData(applicationId, collaborators = Set(developerCollaborator, adminCollaborator))
    await(applicationRepository.save(app))

    val existingCollaborators = app.collaborators
    val userIdToDelete        = existingCollaborators.head.userId

    val appWithOutDeletedCollaborator = await(applicationRepository.removeCollaborator(applicationId, userIdToDelete))
    appWithOutDeletedCollaborator.collaborators must contain only (existingCollaborators.toList.filterNot(_.userId == userIdToDelete): _*)
  }

  "handle ProductionAppPrivacyPolicyLocationChanged correctly" in {
    val applicationId = ApplicationId.random
    val oldLocation   = PrivacyPolicyLocations.InDesktopSoftware
    val newLocation   = PrivacyPolicyLocations.Url("http://example.com")
    val access        = Standard(
      List.empty,
      None,
      None,
      Set.empty,
      None,
      Some(
        ImportantSubmissionData(
          None,
          ResponsibleIndividual.build("bob example", "bob@example.com"),
          Set.empty,
          TermsAndConditionsLocations.InDesktopSoftware,
          oldLocation,
          List.empty
        )
      )
    )
    val app           = anApplicationData(applicationId).copy(access = access)
    await(applicationRepository.save(app))

    val appWithUpdatedPrivacyPolicyLocation = await(applicationRepository.updateApplicationPrivacyPolicyLocation(applicationId, newLocation))
    appWithUpdatedPrivacyPolicyLocation.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, _, privacyPolicyLocation, _))) => privacyPolicyLocation mustBe newLocation
      case _                                                                                            => fail("unexpected access type: " + appWithUpdatedPrivacyPolicyLocation.access)
    }
  }

  "handle ProductionLegacyAppPrivacyPolicyLocationChanged correctly" in {
    val applicationId = ApplicationId.random
    val oldUrl        = "http://example.com/old"
    val newUrl        = "http://example.com/new"
    val access        = Standard(List.empty, None, Some(oldUrl), Set.empty, None, None)
    val app           = anApplicationData(applicationId).copy(access = access)
    await(applicationRepository.save(app))

    val appWithUpdatedPrivacyPolicyLocation = await(applicationRepository.updateLegacyApplicationPrivacyPolicyLocation(applicationId, newUrl))
    appWithUpdatedPrivacyPolicyLocation.access match {
      case Standard(_, _, Some(privacyPolicyUrl), _, _, None) => privacyPolicyUrl mustBe newUrl
      case _                                                  => fail("unexpected access type: " + appWithUpdatedPrivacyPolicyLocation.access)
    }
  }

  "handle ProductionAppTermsConditionsLocationChanged event correctly" in {
    val applicationId = ApplicationId.random
    val oldLocation   = TermsAndConditionsLocations.InDesktopSoftware
    val newLocation   = TermsAndConditionsLocations.Url("http://example.com")
    val access        = Standard(
      List.empty,
      None,
      None,
      Set.empty,
      None,
      Some(
        ImportantSubmissionData(None, ResponsibleIndividual.build("bob example", "bob@example.com"), Set.empty, oldLocation, PrivacyPolicyLocations.InDesktopSoftware, List.empty)
      )
    )
    val app           = anApplicationData(applicationId).copy(access = access)
    await(applicationRepository.save(app))

    val appWithUpdatedTermsConditionsLocation = await(applicationRepository.updateApplicationTermsAndConditionsLocation(applicationId, newLocation))
    appWithUpdatedTermsConditionsLocation.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, termsAndConditionsLocation, _, _))) => termsAndConditionsLocation mustBe newLocation
      case _                                                                                                 => fail("unexpected access type: " + appWithUpdatedTermsConditionsLocation.access)
    }
  }

  "handle ProductionLegacyAppTermsConditionsLocationChanged event correctly" in {
    val applicationId = ApplicationId.random
    val oldUrl        = "http://example.com/old"
    val newUrl        = "http://example.com/new"
    val access        = Standard(List.empty, Some(oldUrl), None, Set.empty, None, None)
    val app           = anApplicationData(applicationId).copy(access = access)
    await(applicationRepository.save(app))

    val appWithUpdatedTermsConditionsLocation = await(applicationRepository.updateLegacyApplicationTermsAndConditionsLocation(applicationId, newUrl))
    appWithUpdatedTermsConditionsLocation.access match {
      case Standard(_, Some(termsAndConditionsUrl), _, _, _, None) => termsAndConditionsUrl mustBe newUrl
      case _                                                       => fail("unexpected access type: " + appWithUpdatedTermsConditionsLocation.access)
    }
  }

  "handle updateApplicationState correctly" in {
    val applicationId           = ApplicationId.random
    val ts                      = FixedClock.now.truncatedTo(ChronoUnit.MILLIS)
    val oldRi                   = ResponsibleIndividual.build("old ri name", "old@example.com")
    val importantSubmissionData =
      ImportantSubmissionData(None, oldRi, Set.empty, TermsAndConditionsLocations.InDesktopSoftware, PrivacyPolicyLocations.InDesktopSoftware, List.empty)
    val access                  = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    val app                     = anApplicationData(applicationId).copy(access = access)

    await(applicationRepository.save(app))
    app.state.name mustBe State.PRODUCTION
    val appWithUpdatedState = await(applicationRepository.updateApplicationState(applicationId, State.PENDING_GATEKEEPER_APPROVAL, ts, adminEmail.text, adminName))
    appWithUpdatedState.state.name mustBe State.PENDING_GATEKEEPER_APPROVAL
    appWithUpdatedState.state.updatedOn mustBe ts
    appWithUpdatedState.state.requestedByEmailAddress mustBe Some(adminEmail.text)
    appWithUpdatedState.state.requestedByName mustBe Some(adminName)
  }

  "handle updateApplicationChangeResponsibleIndividualToSelf correctly" in {
    val applicationId = ApplicationId.random

    val oldRi                   = ResponsibleIndividual.build("old ri name", "old@example.com")
    val submissionId            = SubmissionId.random
    val submissionIndex         = 1
    val importantSubmissionData = ImportantSubmissionData(
      None,
      oldRi,
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List(TermsOfUseAcceptance(oldRi, FixedClock.now, submissionId, submissionIndex))
    )
    val access                  = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    val app                     = anApplicationData(applicationId).copy(access = access)
    await(applicationRepository.save(app))

    val appWithUpdatedRI =
      await(applicationRepository.updateApplicationChangeResponsibleIndividualToSelf(applicationId, adminName, adminEmail, FixedClock.now, submissionId, submissionIndex))

    appWithUpdatedRI.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => {
        importantSubmissionData.responsibleIndividual.fullName.value mustBe adminName
        importantSubmissionData.responsibleIndividual.emailAddress mustBe adminEmail
        importantSubmissionData.termsOfUseAcceptances.size mustBe 2
        val latestAcceptance = importantSubmissionData.termsOfUseAcceptances(1)
        latestAcceptance.responsibleIndividual.fullName.value mustBe adminName
        latestAcceptance.responsibleIndividual.emailAddress mustBe adminEmail
      }
      case _                                                      => fail("unexpected access type: " + appWithUpdatedRI.access)
    }
  }

  "handle NameChanged event correctly" in {
    val applicationId = ApplicationId.random
    val oldName       = "oldName"
    val newName       = "newName"

    val app = anApplicationData(applicationId).copy(name = oldName)
    await(applicationRepository.save(app))

    val appWithUpdatedName = await(applicationRepository.updateApplicationName(applicationId, newName))
    appWithUpdatedName.name mustBe newName
    appWithUpdatedName.normalisedName mustBe newName.toLowerCase

    await(applicationRepository.hardDelete(applicationId))
  }

  "handle updateApplicationChangeResponsibleIndividual" in {
    val applicationId           = ApplicationId.random
    val riName                  = "Mr Responsible"
    val riEmail                 = "ri@example.com".toLaxEmail
    val oldRi                   = ResponsibleIndividual.build("old ri name", "old@example.com")
    val submissionId            = SubmissionId.random
    val submissionIndex         = 1
    val importantSubmissionData = ImportantSubmissionData(
      None,
      oldRi,
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List(TermsOfUseAcceptance(oldRi, FixedClock.now, submissionId, submissionIndex))
    )
    val access                  = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    val app                     = anApplicationData(applicationId).copy(access = access)
    await(applicationRepository.save(app))

    val appWithUpdatedRI = await(applicationRepository.updateApplicationChangeResponsibleIndividual(applicationId, riName, riEmail, FixedClock.now, submissionId, submissionIndex))
    appWithUpdatedRI.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => {
        importantSubmissionData.responsibleIndividual.fullName.value mustBe riName
        importantSubmissionData.responsibleIndividual.emailAddress mustBe riEmail
        importantSubmissionData.termsOfUseAcceptances.size mustBe 2
        val latestAcceptance = importantSubmissionData.termsOfUseAcceptances(1)
        latestAcceptance.responsibleIndividual.fullName.value mustBe riName
        latestAcceptance.responsibleIndividual.emailAddress mustBe riEmail
      }
      case _                                                      => fail("unexpected access type: " + appWithUpdatedRI.access)
    }
  }

  "fetchProdAppStateHistories" should {
    def saveApp(state: State, timeOffset: Duration, isNewJourney: Boolean = true, environment: Environment = Environment.PRODUCTION) = {
      val appId = ApplicationId.random
      val app   = anApplicationData(appId).copy(
        state = ApplicationState(name = state, updatedOn = FixedClock.now),
        access = Standard(importantSubmissionData = isNewJourney match {
          case true  => Some(ImportantSubmissionData(
              None,
              ResponsibleIndividual.build("ri name", "ri@example.com"),
              Set.empty,
              TermsAndConditionsLocations.InDesktopSoftware,
              PrivacyPolicyLocations.InDesktopSoftware,
              List.empty
            ))
          case false => None
        }),
        createdOn = FixedClock.now.plus(timeOffset).truncatedTo(ChronoUnit.MILLIS),
        environment = environment.toString,
        tokens = ApplicationTokens(Token(ClientId.random, "access token"))
      )
      await(applicationRepository.save(app))
      app
    }

    def saveHistoryStatePair(appId: ApplicationId, oldState: State, newState: State, timeOffset: Duration)     = saveHistory(appId, Some(oldState), newState, timeOffset)
    def saveHistory(appId: ApplicationId, maybeOldState: Option[State], newState: State, timeOffset: Duration) = {
      val stateHistory =
        StateHistory(appId, newState, Actors.GatekeeperUser("actor"), maybeOldState, None, FixedClock.now.plus(timeOffset).truncatedTo(ChronoUnit.MILLIS))
      await(stateHistoryRepository.insert(stateHistory))
      stateHistory
    }

    "return app state history correctly for new journey app" in {
      val app           = saveApp(State.PRODUCTION, Duration.ZERO, true)
      val stateHistory1 = saveHistoryStatePair(app.id, State.TESTING, State.PENDING_REQUESTER_VERIFICATION, Duration.ofHours(1))
      val stateHistory2 = saveHistoryStatePair(app.id, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_GATEKEEPER_APPROVAL, Duration.ofHours(2))
      val stateHistory3 = saveHistoryStatePair(app.id, State.PENDING_GATEKEEPER_APPROVAL, State.PRODUCTION, Duration.ofHours(3))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(ApplicationWithStateHistory(app.id, app.name, 2, List(stateHistory1, stateHistory2, stateHistory3)))
    }

    "return app state history correctly for old journey app" in {
      val app           = saveApp(State.PRODUCTION, Duration.ZERO, false)
      val stateHistory1 = saveHistoryStatePair(app.id, State.TESTING, State.PENDING_REQUESTER_VERIFICATION, Duration.ofHours(1))
      val stateHistory2 = saveHistoryStatePair(app.id, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_GATEKEEPER_APPROVAL, Duration.ofHours(2))
      val stateHistory3 = saveHistoryStatePair(app.id, State.PENDING_GATEKEEPER_APPROVAL, State.PRODUCTION, Duration.ofHours(3))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(ApplicationWithStateHistory(app.id, app.name, 1, List(stateHistory1, stateHistory2, stateHistory3)))
    }

    "return app state histories sorted correctly" in {
      val app1        = saveApp(State.PRODUCTION, Duration.ofHours(1))
      val app1History = saveHistory(app1.id, None, State.TESTING, Duration.ofHours(1))

      val app2        = saveApp(State.PRODUCTION, Duration.ofHours(3))
      val app2History = saveHistory(app2.id, None, State.TESTING, Duration.ofHours(3))

      val app3        = saveApp(State.PRODUCTION, Duration.ofHours(2))
      val app3History = saveHistory(app3.id, None, State.TESTING, Duration.ofHours(2))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(
        ApplicationWithStateHistory(app1.id, app1.name, 2, List(app1History)),
        ApplicationWithStateHistory(app3.id, app2.name, 2, List(app3History)),
        ApplicationWithStateHistory(app2.id, app3.name, 2, List(app2History))
      )
    }

    "return only prod app state histories and not sandbox" in {
      val prodApp       = saveApp(State.PRODUCTION, Duration.ofHours(1))
      val stateHistory1 = saveHistoryStatePair(prodApp.id, State.TESTING, State.PENDING_GATEKEEPER_APPROVAL, Duration.ofHours(1))
      val stateHistory2 = saveHistoryStatePair(prodApp.id, State.PENDING_GATEKEEPER_APPROVAL, State.PRODUCTION, Duration.ofHours(2))

      val sandboxApp = saveApp(State.PRODUCTION, Duration.ofHours(1), true, Environment.SANDBOX)
      saveHistory(sandboxApp.id, None, State.PRODUCTION, Duration.ofHours(1))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(ApplicationWithStateHistory(prodApp.id, prodApp.name, 2, List(stateHistory1, stateHistory2)))
    }

    "do not return app state history for a deleted app" in {
      val app = saveApp(State.DELETED, Duration.ZERO, true)
      saveHistoryStatePair(app.id, State.TESTING, State.PENDING_REQUESTER_VERIFICATION, Duration.ofHours(1))
      saveHistoryStatePair(app.id, State.PENDING_REQUESTER_VERIFICATION, State.DELETED, Duration.ofHours(2))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List.empty
    }
  }

  "getSubscriptionsForDeveloper" should {
    val developerEmail1 = "john.doe@example.com"
    val developerEmail2 = "someone-else@example.com"

    val user1 = developerEmail1.developer()
    val user2 = developerEmail2.developer()

    "return only the APIs that the user's apps are subscribed to, without duplicates" in {
      val app1            = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId, users = Set(user1))
      await(applicationRepository.save(app1))
      val app2            = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId, users = Set(user1))
      await(applicationRepository.save(app2))
      val someoneElsesApp = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId, users = Set(user2))
      await(applicationRepository.save(someoneElsesApp))

      val helloWorldApi1 = "hello-world".asIdentifier("1.0")
      val helloWorldApi2 = "hello-world".asIdentifier("2.0")
      val helloVatApi    = "hello-vat".asIdentifier("1.0")
      val helloAgentsApi = "hello-agents".asIdentifier("1.0")

      await(subscriptionRepository.add(app1.id, helloWorldApi1))
      await(subscriptionRepository.add(app1.id, helloVatApi))
      await(subscriptionRepository.add(app2.id, helloWorldApi2))
      await(subscriptionRepository.add(app2.id, helloVatApi))
      await(subscriptionRepository.add(someoneElsesApp.id, helloAgentsApi))

      val result: Set[ApiIdentifier] = await(applicationRepository.getSubscriptionsForDeveloper(user1.userId))

      result mustBe Set(helloWorldApi1, helloVatApi, helloWorldApi2)
    }

    "return empty when the user is not a collaborator of any apps" in {
      val app1 = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId, users = Set(user2))
      val app2 = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId, users = Set(user1))

      await(applicationRepository.save(app1))
      await(applicationRepository.save(app2))

      val api = "hello-world".asIdentifier("1.0")
      await(subscriptionRepository.add(app1.id, api))

      val developerId                = app2.collaborators.head.userId
      val result: Set[ApiIdentifier] = await(applicationRepository.getSubscriptionsForDeveloper(developerId))

      result mustBe Set.empty
    }

    "return empty when the user's apps are not subscribed to any API" in {
      val app = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId, users = Set(user1))
      await(applicationRepository.save(app))

      val developerId                = app.collaborators.head.userId
      val result: Set[ApiIdentifier] = await(applicationRepository.getSubscriptionsForDeveloper(developerId))

      result mustBe Set.empty
    }
  }

  def createAppWithStatusUpdatedOn(
      state: State.State,
      updatedOn: LocalDateTime
    ): ApplicationData =
    anApplicationDataForTest(
      id = ApplicationId.random,
      prodClientId = generateClientId,
      state = ApplicationState(
        state,
        Some("requestorEmail@example.com"),
        Some("requesterName"),
        Some("aVerificationCode"),
        updatedOn
      )
    )

  def aSubscriptionData(
      context: String,
      version: String,
      applicationIds: ApplicationId*
    ) = {
    SubscriptionData(context.asIdentifier(version), Set(applicationIds: _*))
  }

  def anApplicationDataForTest(
      id: ApplicationId,
      prodClientId: ClientId = ClientId("aaa"),
      state: ApplicationState = testingState(),
      access: Access = Standard(),
      grantLength: Int = defaultGrantLength,
      users: Set[Collaborator] = Set(
        "user@example.com".admin()
      ),
      checkInformation: Option[CheckInformation] = None,
      clientSecrets: List[ClientSecret] = List(aClientSecret(hashedSecret = "hashed-secret"))
    ): ApplicationData = {

    aNamedApplicationData(
      id,
      s"myApp-${id.value}",
      prodClientId,
      state,
      access,
      users,
      checkInformation,
      clientSecrets,
      grantLength
    )
  }

  def aNamedApplicationData(
      id: ApplicationId,
      name: String,
      prodClientId: ClientId = ClientId("aaa"),
      state: ApplicationState = testingState(),
      access: Access = Standard(),
      users: Set[Collaborator] = Set("user@example.com".admin()),
      checkInformation: Option[CheckInformation] = None,
      clientSecrets: List[ClientSecret] = List(aClientSecret(hashedSecret = "hashed-secret")),
      grantLength: Int = defaultGrantLength
    ): ApplicationData = {

    ApplicationData(
      id,
      name,
      name.toLowerCase,
      users,
      Some("description"),
      "myapplication",
      ApplicationTokens(
        Token(prodClientId, generateAccessToken, clientSecrets)
      ),
      state,
      access,
      FixedClock.now,
      Some(FixedClock.now),
      grantLength = grantLength,
      checkInformation = checkInformation
    )
  }

  def aClientSecret(id: String = UUID.randomUUID().toString, name: String = "", lastAccess: Option[LocalDateTime] = None, hashedSecret: String = "") =
    ClientSecret(
      id = id,
      name = name,
      lastAccess = lastAccess,
      hashedSecret = hashedSecret,
      createdOn = FixedClock.now
    )

}
