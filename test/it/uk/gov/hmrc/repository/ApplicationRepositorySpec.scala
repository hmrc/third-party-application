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

package uk.gov.hmrc.repository

import java.util.UUID

import org.joda.time.DateTime
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.IndexVerification
import uk.gov.hmrc.models._
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import common.uk.gov.hmrc.testutils.ApplicationStateUtil

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random.{alphanumeric, nextString}

class ApplicationRepositorySpec extends UnitSpec with MongoSpecSupport
  with BeforeAndAfterEach with BeforeAndAfterAll with ApplicationStateUtil with IndexVerification
  with MockitoSugar with Eventually {

  private val reactiveMongoComponent = new ReactiveMongoComponent { override def mongoConnector: MongoConnector = mongoConnectorForTest }

  private val applicationRepository = new ApplicationRepository(reactiveMongoComponent)
  private val subscriptionRepository = new SubscriptionRepository(reactiveMongoComponent)

  private def generateClientId = alphanumeric.take(10).mkString

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

  "fetchByClientId" should {

    "retrieve the application for a given client id when it is matched for sandbox client id" in {

      val application1 = anApplicationData(UUID.randomUUID(), "aaa", "111", productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(UUID.randomUUID(), "zzz", "999", productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByClientId(application1.tokens.sandbox.clientId))

      retrieved shouldBe Some(application1)

    }

    "retrieve the application for a given client id when it is matched for production client id" in {

      val application1 = anApplicationData(UUID.randomUUID(), "aaa", "111", productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(UUID.randomUUID(), "zzz", "999", productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByClientId(application2.tokens.production.clientId))

      retrieved shouldBe Some(application2)

    }

  }

  "fetchByServerToken" should {

    "retrieve the application when it is matched for sandbox access token" in {

      val application1 = anApplicationData(UUID.randomUUID(), "aaa", "111", productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(UUID.randomUUID(), "zzz", "999", productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByServerToken(application1.tokens.sandbox.accessToken))

      retrieved shouldBe Some(application1)
    }

    "retrieve the application when it is matched for production access token" in {

      val application1 = anApplicationData(UUID.randomUUID(), "aaa", "111", productionState("requestorEmail@example.com"))
      val application2 = anApplicationData(UUID.randomUUID(), "zzz", "999", productionState("requestorEmail@example.com"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchByServerToken(application2.tokens.production.accessToken))

      retrieved shouldBe Some(application2)
    }
  }

  "fetchAllForEmailAddress" should {

    "retrieve all the applications for a given collaborator email address" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(applicationRepository.fetchAllForEmailAddress("user@example.com"))

      retrieved shouldBe Seq(application1, application2)
    }

  }

  "fetchStandardNonTestingApps" should {
    "retrieve all the standard applications not in TESTING state" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId,
        state = pendingRequesterVerificationState("user1"))
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId,
        state = productionState("user2"))
      val application4 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId,
        state = pendingRequesterVerificationState("user2"))

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

      val upliftedApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
        .copy(normalisedName = applicationNormalisedName, state = pendingGatekeeperApprovalState("email@example.com"))
      val testingApplication = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
        .copy(normalisedName = applicationNormalisedName, state = testingState())

      await(applicationRepository.save(upliftedApplication))
      await(applicationRepository.save(testingApplication))

      val retrieved = await(applicationRepository.fetchNonTestingApplicationByName(applicationName))

      retrieved shouldBe Some(upliftedApplication)
    }

    "retrieve None when no uplifted application exist for that name" in {
      val applicationName = "appName"
      val applicationNormalizedName = "appname"

      val testingApplication = anApplicationData(UUID.randomUUID()).copy(normalisedName = applicationNormalizedName, state = testingState())
      await(applicationRepository.save(testingApplication))

      val retrieved = await(applicationRepository.fetchNonTestingApplicationByName(applicationName))

      retrieved shouldBe None
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

      verifyApplications(await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)), State.PENDING_REQUESTER_VERIFICATION, 1)
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated before the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, expiryOnTheDayBefore)
      await(applicationRepository.save(application))

      verifyApplications(await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)), State.PENDING_REQUESTER_VERIFICATION, 1)
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated on the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)
      await(applicationRepository.save(application))

      verifyApplications(await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)), State.PENDING_REQUESTER_VERIFICATION, 1)
    }

    "retrieve no application with PENDING_REQUESTER_VERIFICATION state that have been updated after the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, expiryOnTheDayAfter)
      await(applicationRepository.save(application))

      verifyApplications(await(applicationRepository.fetchAllByStatusDetails(State.PENDING_REQUESTER_VERIFICATION, dayOfExpiry)), State.PENDING_REQUESTER_VERIFICATION, 0)
    }

  }

  "fetchVerifiableBy" should {

    "retrieve the application with verificationCode when in pendingRequesterVerification state" in {
      val application = anApplicationData(UUID.randomUUID(), "aaa", "111", state = pendingRequesterVerificationState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode))
      retrieved shouldBe Some(application)
    }

    "retrieve the application with verificationCode when in production state" in {
      val application = anApplicationData(UUID.randomUUID(), "aaa", "111", state = productionState("requestorEmail@example.com"))
      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(generatedVerificationCode))
      retrieved shouldBe Some(application)
    }

    "not retrieve the application with an unknown verificationCode" in {
      val application = anApplicationData(UUID.randomUUID(), "aaa", "111", state = pendingRequesterVerificationState("requestorEmail@example.com"))
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

      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version", application1.id)))

      val result = await(applicationRepository.fetchAllWithNoSubscriptions())

      result shouldBe Seq(application2)
    }
  }

  "fetchAll" should {
    "fetch all existing applications" in {

      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(applicationRepository.fetchAll()) shouldBe Seq(application1, application2)
    }
  }

  "fetchAllForContext" should {
    "fetch only those applications when the context matches" in {

      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
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
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
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
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application3 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id, application3.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier(APIIdentifier("context", "version-2")))

      result shouldBe Seq(application2, application3)
    }

    "fetch no applications when the context and version do not match" in {
      val application1 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      val application2 = anApplicationData(id = UUID.randomUUID(), prodClientId = generateClientId, sandboxClientId = generateClientId)
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-1", application1.id)))
      await(subscriptionRepository.insert(aSubscriptionData("context", "version-2", application2.id)))
      await(subscriptionRepository.insert(aSubscriptionData("other", "version-2", application1.id, application2.id)))

      val result = await(applicationRepository.fetchAllForApiIdentifier(APIIdentifier("other", "version-1")))

      result shouldBe Seq()
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
        Index(key = Seq("tokens.production.clientId" -> Ascending), name = Some("productionTokenClientIdIndex"), unique = true, background = true),
        Index(key = Seq("tokens.sandbox.clientId" -> Ascending), name = Some("sandboxTokenClientIdIndex"), unique = true, background = true),
        Index(key = Seq("access.overrides" -> Ascending), name = Some("accessOverridesIndex"), background = true),
        Index(key = Seq("access.accessType" -> Ascending), name = Some("accessTypeIndex"), background = true),
        Index(key = Seq("collaborators.emailAddress" -> Ascending), name = Some("collaboratorsEmailAddressIndex"), background = true)
      )

      verifyIndexesVersionAgnostic(applicationRepository, expectedIndexes)
    }
  }

  def createAppWithStatusUpdatedOn(state: State.State, updatedOn: DateTime) = anApplicationData(
    id = UUID.randomUUID(),
    prodClientId = generateClientId,
    sandboxClientId = generateClientId,
    state = ApplicationState(state, Some("requestorEmail@example.com"), Some("aVerificationCode"), updatedOn)
  )

  def aSubscriptionData(context: String, version: String, applicationIds: UUID*) = {
    SubscriptionData(APIIdentifier(context, version), Set(applicationIds: _*))
  }

  def anApplicationData(id: UUID,
                        prodClientId: String = "aaa",
                        sandboxClientId: String = "111",
                        state: ApplicationState = testingState(),
                        access: Access = Standard(Seq.empty, None, None),
                        user: String = "user@example.com"): ApplicationData = {

    ApplicationData(
      id,
      s"myApp-$id",
      s"myapp-$id",
      Set(Collaborator(user, Role.ADMINISTRATOR)),
      Some("description"),
      "username",
      "password",
      "myapplication",
      ApplicationTokens(
        EnvironmentToken(prodClientId, nextString(5), nextString(5)),
        EnvironmentToken(sandboxClientId, nextString(5), nextString(5))),
      state,
      access)
  }

}
