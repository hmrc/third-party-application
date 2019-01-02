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
import common.uk.gov.hmrc.testutils.ApplicationStateUtil

import scala.concurrent.ExecutionContext.Implicits.global

class SubscriptionRepositorySpec extends UnitSpec with MockitoSugar with MongoSpecSupport with IndexVerification
  with BeforeAndAfterEach with BeforeAndAfterAll with ApplicationStateUtil with Eventually {

  private val reactiveMongoComponent = new ReactiveMongoComponent { override def mongoConnector: MongoConnector = mongoConnectorForTest }

  private val repository = new SubscriptionRepository(reactiveMongoComponent)

  override def beforeEach() {
    await(repository.drop)
    await(repository.ensureIndexes)
  }

  override protected def afterAll() {
    await(repository.drop)
  }

  "add" should {

    "create an entry" in {
      val applicationId = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")

      val result = await(repository.add(applicationId, apiIdentifier))

      result shouldBe HasSucceeded
    }

    "create multiple subscriptions" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(repository.add(application1, apiIdentifier))

      val result = await(repository.add(application2, apiIdentifier))

      result shouldBe HasSucceeded
    }
  }

  "remove" should {
    "delete the subscription" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(repository.add(application1, apiIdentifier))
      await(repository.add(application2, apiIdentifier))

      val result = await(repository.remove(application1, apiIdentifier))

      result shouldBe HasSucceeded
      await(repository.isSubscribed(application1, apiIdentifier)) shouldBe false
      await(repository.isSubscribed(application2, apiIdentifier)) shouldBe true
    }

    "not fail when deleting a non-existing subscription" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(repository.add(application1, apiIdentifier))

      val result = await(repository.remove(application2, apiIdentifier))

      result shouldBe HasSucceeded
      await(repository.isSubscribed(application1, apiIdentifier)) shouldBe true
    }
  }

  "find all" should {
    "retrieve all versions subscriptions" in {
      val application1 = UUID.randomUUID()
      val application2 = UUID.randomUUID()
      val apiIdentifierA = APIIdentifier("some-context-a", "1.0.0")
      val apiIdentifierB = APIIdentifier("some-context-b", "1.0.2")
      await(repository.add(application1, apiIdentifierA))
      await(repository.add(application2, apiIdentifierA))
      await(repository.add(application2, apiIdentifierB))
      val retrieved = await(repository.findAll())
      retrieved shouldBe Seq(
        subscriptionData("some-context-a", "1.0.0", application1, application2),
        subscriptionData("some-context-b", "1.0.2", application2))
    }
  }

  "isSubscribed" should {

    "return true when the application is subscribed" in {
      val applicationId = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")
      await(repository.add(applicationId, apiIdentifier))

      val isSubscribed = await(repository.isSubscribed(applicationId, apiIdentifier))

      isSubscribed shouldBe true
    }

    "return false when the application is not subscribed" in {
      val applicationId = UUID.randomUUID()
      val apiIdentifier = APIIdentifier("some-context", "1.0.0")

      val isSubscribed = await(repository.isSubscribed(applicationId, apiIdentifier))

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
      await(repository.add(application1, api1))
      await(repository.add(application1, api2))
      await(repository.add(application2, api3))

      val result = await(repository.getSubscriptions(application1))

      result shouldBe Seq(api1, api2)
    }

    "return empty when the application is not subscribed to any API" in {
      val result = await(repository.getSubscriptions(application1))

      result shouldBe Seq.empty
    }
  }

  "The 'subscription' collection" should {
    "have all the indexes" in {
      val expectedIndexes = Set(
        Index(key = Seq("applications" -> Ascending), name = Some("applications"), unique = false, background = true),
        Index(key = Seq("apiIdentifier.context" -> Ascending), name = Some("context"), unique = false, background = true),
        Index(key = Seq("apiIdentifier.context" -> Ascending, "apiIdentifier.version" -> Ascending), name = Some("context_version"), unique = true, background = true),
        Index(key = Seq("_id" -> Ascending), name = Some("_id_"), unique = false, background = false))

      verifyIndexesVersionAgnostic(repository, expectedIndexes)
    }
  }

  def subscriptionData(apiContext: String, version: String, applicationIds: UUID*) = {
    SubscriptionData(
      APIIdentifier(apiContext, version),
      Set(applicationIds: _*))
  }
}