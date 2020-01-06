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

package it.uk.gov.hmrc.thirdpartyapplication.repository

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.scalatest.concurrent.Eventually
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random.{alphanumeric, nextString}

class SubscriptionRepositorySpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with IndexVerification
  with BeforeAndAfterEach with BeforeAndAfterAll with ApplicationStateUtil with Eventually with TableDrivenPropertyChecks {

  implicit val s : ActorSystem = ActorSystem("test")
  implicit val m : Materializer = ActorMaterializer()

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val subscriptionRepository = new SubscriptionRepository(reactiveMongoComponent)
  private val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

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

  "add" should {

    "create an entry" in {
      val applicationId = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")

      val result = await(subscriptionRepository.add(applicationId, apiIdentifier))

      result shouldBe HasSucceeded
    }

    "create multiple subscriptions" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))

      val result = await(subscriptionRepository.add(application2, apiIdentifier))

      result shouldBe HasSucceeded
    }
  }

  "remove" should {
    "delete the subscription" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))
      await(subscriptionRepository.add(application2, apiIdentifier))

      val result = await(subscriptionRepository.remove(application1, apiIdentifier))

      result shouldBe HasSucceeded
      await(subscriptionRepository.isSubscribed(application1, apiIdentifier)) shouldBe false
      await(subscriptionRepository.isSubscribed(application2, apiIdentifier)) shouldBe true
    }

    "not fail when deleting a non-existing subscription" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))

      val result = await(subscriptionRepository.remove(application2, apiIdentifier))

      result shouldBe HasSucceeded
      await(subscriptionRepository.isSubscribed(application1, apiIdentifier)) shouldBe true
    }
  }

  "find all" should {
    "retrieve all versions subscriptions" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifierA = APIIdentifier("some-context-a", "1.0.0")
      val apiIdentifierB = APIIdentifier("some-context-b", "1.0.2")
      await(subscriptionRepository.add(application1, apiIdentifierA))
      await(subscriptionRepository.add(application2, apiIdentifierA))
      await(subscriptionRepository.add(application2, apiIdentifierB))
      val retrieved = await(subscriptionRepository.findAll())
      retrieved shouldBe Seq(
        subscriptionData("some-context-a", "1.0.0", application1, application2),
        subscriptionData("some-context-b", "1.0.2", application2))
    }
  }

  "isSubscribed" should {

    "return true when the application is subscribed" in {
      val applicationId = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(subscriptionRepository.add(applicationId, apiIdentifier))

      val isSubscribed = await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier))

      isSubscribed shouldBe true
    }

    "return false when the application is not subscribed" in {
      val applicationId = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")

      val isSubscribed = await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier))

      isSubscribed shouldBe false
    }
  }

  "getSubscriptions" should {
    val application1 = UUID.randomUUID()
    val application2 = UUID.randomUUID()
    val api1 = APIIdentifier("some-context", "1.0")
    val api2 = APIIdentifier("some-context", "2.0")
    val api3 = APIIdentifier("some-context", "3.0")

    "return the subscribed APIs" in {
      await(subscriptionRepository.add(application1, api1))
      await(subscriptionRepository.add(application1, api2))
      await(subscriptionRepository.add(application2, api3))

      val result = await(subscriptionRepository.getSubscriptions(application1))

      result shouldBe Seq(api1, api2)
    }

    "return empty when the application is not subscribed to any API" in {
      val result = await(subscriptionRepository.getSubscriptions(application1))

      result shouldBe Seq.empty
    }
  }

  "getSubscribers" should {
    val application1 = UUID.randomUUID()
    val application2 = UUID.randomUUID()
    val api1 = APIIdentifier("some-context", "1.0")
    val api2 = APIIdentifier("some-context", "2.0")
    val api3 = APIIdentifier("some-context", "3.0")

    def saveSubscriptions(): HasSucceeded = {
      await(subscriptionRepository.add(application1, api1))
      await(subscriptionRepository.add(application1, api2))
      await(subscriptionRepository.add(application2, api2))
      await(subscriptionRepository.add(application2, api3))
    }

    "return an empty set when the API doesn't have any subscribers" in {
      saveSubscriptions()

      val applications: Set[UUID] = await(subscriptionRepository.getSubscribers(APIIdentifier("some-context", "4.0")))

      applications should have size 0
    }

    "return the IDs of the applications subscribed to the given API" in {
      saveSubscriptions()
      val scenarios = Table(
        ("apiIdentifier", "expectedApplications"),
        (APIIdentifier("some-context", "1.0"), Seq(application1)),
        (APIIdentifier("some-context", "2.0"), Seq(application1, application2)),
        (APIIdentifier("some-context", "3.0"), Seq(application2))
      )

      forAll(scenarios) { (apiIdentifier, expectedApplications) =>
        val applications: Set[UUID] = await(subscriptionRepository.getSubscribers(apiIdentifier))
        applications should contain only (expectedApplications: _*)
      }
    }
  }

  "The 'subscription' collection" should {
    "have all the indexes" in {
      val expectedIndexes = Set(
        Index(key = Seq("applications" -> Ascending), name = Some("applications"), unique = false, background = true),
        Index(key = Seq("apiIdentifier.context" -> Ascending), name = Some("context"), unique = false, background = true),
        Index(key =
          Seq("apiIdentifier.context" -> Ascending, "apiIdentifier.version" -> Ascending), name = Some("context_version"), unique = true, background = true),
        Index(key = Seq("_id" -> Ascending), name = Some("_id_"), unique = false, background = false))

      verifyIndexesVersionAgnostic(subscriptionRepository, expectedIndexes)
    }
  }

  "Get API Version Collaborators" should {
    "return email addresses" in {

      val app1 = anApplicationData(id = UUID.randomUUID(), clientId = generateClientId, user = Seq("match1@example.com", "match2@example.com"))
      await(applicationRepository.save(app1))

      val app2 = anApplicationData(id = UUID.randomUUID(), clientId = generateClientId, user = Seq("match3@example.com"))
      await(applicationRepository.save(app2))

      val doNotMatchApp = anApplicationData(id = UUID.randomUUID(), clientId = generateClientId, user = Seq("donotmatch@example.com"))
      await(applicationRepository.save(doNotMatchApp))

      val api1 = APIIdentifier("some-context-api1", "1.0")
      await(subscriptionRepository.add(app1.id, api1))
      await(subscriptionRepository.add(app2.id, api1))

      val doNotMatchApi = APIIdentifier("some-context-donotmatchapi", "1.0")
      await(subscriptionRepository.add(doNotMatchApp.id, doNotMatchApi))

      val result = await(subscriptionRepository.searchCollaborators(api1.context, api1.version, None))

      val expectedEmails = app1.collaborators.map(c => c.emailAddress) ++ app2.collaborators.map(c => c.emailAddress)
      result.toSet shouldBe expectedEmails
    }

    "filter by collaborators and api version" in {

      val matchEmail = "match@example.com"
      val partialEmailToMatch = "match"
      val app1 = anApplicationData(
        id = UUID.randomUUID(),
        clientId = generateClientId,
        user = Seq(matchEmail, "donot@example.com"))

      await(applicationRepository.save(app1))

      val api1 = APIIdentifier("some-context-api", "1.0")
      await(subscriptionRepository.add(app1.id, api1))

      val result = await(subscriptionRepository.searchCollaborators(api1.context, api1.version, Some(partialEmailToMatch)))

      result.toSet shouldBe Set(matchEmail)
    }
  }

  def subscriptionData(apiContext: String, version: String, applicationIds: UUID*) = {
    SubscriptionData(
      APIIdentifier(apiContext, version),
      Set(applicationIds: _*))
  }

  def anApplicationData(id: UUID,
                        clientId: String = "aaa",
                        state: ApplicationState = testingState(),
                        access: Access = Standard(Seq.empty, None, None),
                        user: Seq[String] = Seq("user@example.com"),
                        checkInformation: Option[CheckInformation] = None): ApplicationData = {

    aNamedApplicationData(id, s"myApp-$id", clientId, state, access, user, checkInformation)
  }

  def aNamedApplicationData(id: UUID,
                            name: String,
                            clientId: String = "aaa",
                            state: ApplicationState = testingState(),
                            access: Access = Standard(Seq.empty, None, None),
                            user: Seq[String] = Seq("user@example.com"),
                            checkInformation: Option[CheckInformation] = None): ApplicationData = {

    val collaborators = user.map(email => Collaborator(email, Role.ADMINISTRATOR)).toSet

    ApplicationData(
      id,
      name,
      name.toLowerCase,
      collaborators,
      Some("description"),
      "username",
      "password",
      "myapplication",
      ApplicationTokens(EnvironmentToken(clientId, generateWso2ClientSecret, generateAccessToken)),
      state,
      access,
      DateTimeUtils.now,
      Some(DateTimeUtils.now),
      checkInformation = checkInformation)
  }

  private def generateClientId = {
    val testClientIdLength = 10
    alphanumeric.take(testClientIdLength).mkString
  }

  private def generateWso2ClientSecret = {
    val testClientSecretLength = 5
    nextString(testClientSecretLength)
  }

  private def generateAccessToken = {
    val testAccessTokenLength = 5
    nextString(testAccessTokenLength)
  }

}
