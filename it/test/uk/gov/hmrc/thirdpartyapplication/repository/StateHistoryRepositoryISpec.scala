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

import java.time.Duration
import scala.concurrent.ExecutionContext.Implicits.global

import org.mongodb.scala.bson.collection.immutable.Document
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import play.api.libs.json._
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.util._

object StateHistoryRepositoryISpecExample extends FixedClock {
  val appId        = ApplicationId.random
  val actor: Actor = Actors.AppCollaborator("admin@example.com".toLaxEmail)
  val stateHistory = StateHistory(appId, State.TESTING, actor, changedAt = instant)

  val json = Json.obj(
    "applicationId" -> JsString(appId.toString()),
    "state"         -> "TESTING",
    "actor"         -> Json.obj(
      "email"     -> "admin@example.com",
      "actorType" -> "COLLABORATOR"
    ),
    "changedAt"     -> MongoJavatimeHelper.asJsValue(instant)
  )
}

class StateHistoryRepositoryISpec
    extends AsyncHmrcSpec
    with MongoSupport
    with CleanMongoCollectionSupport
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with Eventually
    with FixedClock {

  import StateHistoryRepositoryISpecExample._

  private val repository = new StateHistoryRepository(mongoComponent)

  "mongo formats" should {
    import StateHistoryRepository.MongoFormats.formatStateHistory

    "write to json" in {
      Json.toJson(stateHistory) shouldBe json
    }

    "read from json" in {
      Json.fromJson[StateHistory](json).get shouldBe stateHistory
    }
  }

  "mongo formatting in scope for repository" should {
    import org.mongodb.scala.Document
    import org.mongodb.scala.result.InsertOneResult

    def saveMongoJson(rawJson: JsObject): InsertOneResult = {
      await(mongoDatabase.getCollection("stateHistory").insertOne(Document(rawJson.toString())).toFuture())
    }

    "read existing document from mongo" in {
      saveMongoJson(json)
      val result = await(repository.collection.find(Document()).toFuture())
      result shouldBe List(stateHistory)
    }
  }

  "insert" should {

    "Save a state history" in {

      val stateHistory = StateHistory(ApplicationId.random, State.TESTING, actor, changedAt = instant)

      val result = await(repository.insert(stateHistory))

      result shouldBe stateHistory
      val savedStateHistories = await(repository.collection.find(Document()).toFuture())
      savedStateHistories shouldBe Seq(stateHistory)
    }
  }

  "fetchLatestByApplicationIdAndState" should {

    "Return the state history of the application" in {

      val applicationId   = ApplicationId.random
      val pendingHistory1 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = instant.minus(Duration.ofDays(5)))
      val approvedHistory = StateHistory(applicationId, State.PENDING_REQUESTER_VERIFICATION, actor, changedAt = instant)
      val pendingHistory2 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = instant)
      val pendingHistory3 = StateHistory(ApplicationId.random, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = instant)

      await(repository.insert(pendingHistory1))
      await(repository.insert(approvedHistory))
      await(repository.insert(pendingHistory2))
      await(repository.insert(pendingHistory3))

      val result = await(repository.fetchLatestByStateForApplication(applicationId, State.PENDING_GATEKEEPER_APPROVAL))

      result shouldBe Some(pendingHistory2)
    }
  }

  "deleteByApplicationId" should {

    "Delete the state histories of the application" in {

      val applicationId          = ApplicationId.random
      val stateHistory1          = StateHistory(applicationId, State.TESTING, actor, changedAt = instant)
      val stateHistory2          = StateHistory(applicationId, State.PRODUCTION, actor, changedAt = instant.plusMillis(1))
      val anotherAppStateHistory = StateHistory(ApplicationId.random, State.TESTING, actor, changedAt = instant)
      await(repository.insert(stateHistory1))
      await(repository.insert(stateHistory2))
      await(repository.insert(anotherAppStateHistory))

      await(repository.deleteByApplicationId(applicationId))

      await(repository.collection.find(Document()).toFuture()) shouldBe Seq(anotherAppStateHistory)
    }
  }

  "insert" should {

    "insert a StateHistory record" in {
      val requesterEmail = "bill.badger@rupert.com".toLaxEmail
      val appId          = ApplicationId.random
      val ts             = instant
      val actor: Actor   = Actors.AppCollaborator(requesterEmail)

      val stateHistory = StateHistory(appId, State.PENDING_GATEKEEPER_APPROVAL, actor, Some(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION), changedAt = ts)

      val result = await(repository.insert(stateHistory))

      result shouldBe stateHistory
      val savedStateHistories = await(repository.collection.find(Document()).toFuture())
      savedStateHistories shouldBe Seq(stateHistory)
    }
  }
  "fetchDeletedByApplicationIds" should {
    "fetch a single deleted application" in {
      val requesterEmail = "bill.badger@rupert.com".toLaxEmail
      val appId          = ApplicationId.random
      val ts             = instant
      val actor: Actor   = Actors.AppCollaborator(requesterEmail)

      val stateHistory = StateHistory(appId, State.DELETED, actor, Some(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION), changedAt = ts)

      val result = await(repository.insert(stateHistory))
      result shouldBe stateHistory

      val retrieved = await(repository.fetchDeletedByApplicationIds(List(appId, ApplicationId.random)))
      retrieved.size shouldBe 1
      retrieved.head shouldBe stateHistory
    }
    "fetch multiple deleted applications" in {
      val requesterEmail = "bill.badger@rupert.com".toLaxEmail
      val appId          = ApplicationId.random
      val appId2         = ApplicationId.random
      val appId3         = ApplicationId.random
      val ts             = instant
      val actor: Actor   = Actors.AppCollaborator(requesterEmail)

      val stateHistory  = StateHistory(appId, State.DELETED, actor, Some(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION), changedAt = ts)
      val stateHistory2 = stateHistory.copy(applicationId = appId2)
      val stateHistory3 = stateHistory.copy(applicationId = appId3)

      await(repository.insert(stateHistory))
      await(repository.insert(stateHistory2))
      await(repository.insert(stateHistory3))

      val retrieved = await(repository.fetchDeletedByApplicationIds(List(appId, appId2, appId3)))
      retrieved.size shouldBe 3
      retrieved should contain(stateHistory)
      retrieved should contain(stateHistory2)
      retrieved should contain(stateHistory3)
    }

    "fetch multiple deleted applications and none in other states" in {
      val requesterEmail = "bill.badger@rupert.com".toLaxEmail
      val appId          = ApplicationId.random
      val appId2         = ApplicationId.random
      val appId3         = ApplicationId.random
      val ts             = instant
      val actor: Actor   = Actors.AppCollaborator(requesterEmail)

      val stateHistory  = StateHistory(appId, State.DELETED, actor, Some(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION), changedAt = ts)
      val stateHistory2 = stateHistory.copy(applicationId = appId2)
      val stateHistory3 = stateHistory.copy(applicationId = appId3, state = State.PENDING_GATEKEEPER_APPROVAL)

      await(repository.insert(stateHistory))
      await(repository.insert(stateHistory2))
      await(repository.insert(stateHistory3))

      val retrieved = await(repository.fetchDeletedByApplicationIds(List(appId, appId2, appId3)))
      retrieved.size shouldBe 2
      retrieved should contain(stateHistory)
      retrieved should contain(stateHistory2)
      retrieved should not contain stateHistory3
    }
  }
}
