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
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.domain.models.StateHistory
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actor
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

class StateHistoryRepositoryISpec extends AsyncHmrcSpec with MongoSupport with CleanMongoCollectionSupport
    with BeforeAndAfterEach with BeforeAndAfterAll with Eventually with FixedClock {

  private val repository = new StateHistoryRepository(mongoComponent)
  val actor: Actor       = Actors.AppCollaborator("admin@example.com".toLaxEmail)

  "insert" should {

    "Save a state history" in {

      val stateHistory = StateHistory(ApplicationId.random, State.TESTING, actor, changedAt = now)

      val result = await(repository.insert(stateHistory))

      result shouldBe stateHistory
      val savedStateHistories = await(repository.findAll)
      savedStateHistories shouldBe List(stateHistory)
    }
  }

  "fetchByApplicationId" should {

    "Return the state history of the application" in {

      val applicationId          = ApplicationId.random
      val stateHistory           = StateHistory(applicationId, State.TESTING, actor, changedAt = now)
      val anotherAppStateHistory = StateHistory(ApplicationId.random, State.TESTING, actor, changedAt = now)
      await(repository.insert(stateHistory))
      await(repository.insert(anotherAppStateHistory))

      val result = await(repository.fetchByApplicationId(applicationId))

      result shouldBe List(stateHistory)
    }
  }

  "fetchByState" should {

    "Return the state history of the application" in {

      val applicationId   = ApplicationId.random
      val pendingHistory1 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = now.minusDays(5))
      val approvedHistory = StateHistory(applicationId, State.PENDING_REQUESTER_VERIFICATION, actor, changedAt = now)
      val pendingHistory2 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = now)
      val pendingHistory3 = StateHistory(ApplicationId.random, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = now)

      await(repository.insert(pendingHistory1))
      await(repository.insert(approvedHistory))
      await(repository.insert(pendingHistory2))
      await(repository.insert(pendingHistory3))

      val result = await(repository.fetchByState(State.PENDING_GATEKEEPER_APPROVAL))

      result shouldBe List(pendingHistory1, pendingHistory2, pendingHistory3)
    }
  }

  "fetchLatestByApplicationIdAndState" should {

    "Return the state history of the application" in {

      val applicationId   = ApplicationId.random
      val pendingHistory1 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = now.minusDays(5))
      val approvedHistory = StateHistory(applicationId, State.PENDING_REQUESTER_VERIFICATION, actor, changedAt = now)
      val pendingHistory2 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = now)
      val pendingHistory3 = StateHistory(ApplicationId.random, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = now)

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
      val stateHistory           = StateHistory(applicationId, State.TESTING, actor, changedAt = now)
      val anotherAppStateHistory = StateHistory(ApplicationId.random, State.TESTING, actor, changedAt = now)
      await(repository.insert(stateHistory))
      await(repository.insert(anotherAppStateHistory))

      await(repository.deleteByApplicationId(applicationId))

      await(repository.findAll) shouldBe List(anotherAppStateHistory)
    }
  }

  "insert" should {

    "insert a StateHistory record" in {
      val requesterEmail = "bill.badger@rupert.com".toLaxEmail
      val appId          = ApplicationId.random
      val ts             = now
      val actor: Actor   = Actors.AppCollaborator(requesterEmail)

      val stateHistory = StateHistory(appId, State.PENDING_GATEKEEPER_APPROVAL, actor, Some(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION), changedAt = ts)

      val result = await(repository.insert(stateHistory))

      result shouldBe stateHistory
      val savedStateHistories = await(repository.findAll)
      savedStateHistories shouldBe List(stateHistory)
    }
  }

}
