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

package uk.gov.hmrc.thirdpartyapplication.models

import org.scalatest.BeforeAndAfterEach

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.util.{HmrcSpec}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

class ApplicationStateSpec extends HmrcSpec with ApplicationStateUtil with BeforeAndAfterEach with FixedClock {

  val upliftRequestedByEmail = "requester@example.com"
  val upliftRequestedByName  = "Mrs Requester"

  "state transition from TESTING " should {
    val startingState = testingState().copy(updatedOn = now.minusHours(24L))
    "move application to PENDING_GATEKEEPER_APPROVAL state" in {
      val resultState = startingState.toPendingGatekeeperApproval(upliftRequestedByEmail, upliftRequestedByName, clock)

      resultState.name shouldBe State.PENDING_GATEKEEPER_APPROVAL
      resultState.requestedByEmailAddress shouldBe Some(upliftRequestedByEmail)
      resultState.requestedByName shouldBe Some(upliftRequestedByName)
      resultState.verificationCode shouldBe None
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "fail when application state is changed to PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toProduction(clock))
    }

    "fail when application state is changed to PRE_PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toPreProduction(clock))
    }

    "fail when application state is changed to PENDING_REQUESTER_VERIFICATION" in {
      intercept[InvalidStateTransition](startingState.toPendingRequesterVerification(clock))
    }
  }

  "state transition from PENDING_GATEKEEPER_APPROVAL " should {
    val startingState = pendingGatekeeperApprovalState(upliftRequestedByEmail).copy(updatedOn = now.minusHours(24L))
    "move application to PENDING_REQUESTER_VERIFICATION state" in {
      val resultState = startingState.toPendingRequesterVerification(clock)

      resultState.name shouldBe State.PENDING_REQUESTER_VERIFICATION
      resultState.requestedByEmailAddress shouldBe Some(upliftRequestedByEmail)
      resultState.verificationCode shouldBe defined
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "fail when application state is changed to PRE_PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toPreProduction(clock))
    }

    "fail when application state is changed to PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toProduction(clock))
    }

    "fail when application state is changed to PENDING_GATEKEEPER_APPROVAL" in {
      intercept[InvalidStateTransition](startingState.toPendingGatekeeperApproval(upliftRequestedByEmail, upliftRequestedByName, clock))
    }
  }

  "state transition from PENDING_REQUESTER_VERIFICATION " should {
    val startingState = pendingRequesterVerificationState(upliftRequestedByEmail).copy(updatedOn = now.minusHours(24L))
    "move application to PRE_PRODUCTION state" in {
      val resultState = startingState.toPreProduction(clock)

      resultState.name shouldBe State.PRE_PRODUCTION
      resultState.requestedByEmailAddress shouldBe Some(upliftRequestedByEmail)
      resultState.verificationCode shouldBe defined
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }
    "fail when application state is changed to PENDING_GATEKEEPER_APPROVAL" in {
      intercept[InvalidStateTransition](startingState.toPendingGatekeeperApproval(upliftRequestedByEmail, upliftRequestedByName, clock))
    }
    "fail when application state is changed to PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toProduction(clock))
    }
    "fail when application state is changed to PENDING_REQUESTER_VERIFICATION" in {
      intercept[InvalidStateTransition](startingState.toPendingRequesterVerification(clock))
    }
  }

  "state transition from PRE_PRODUCTION " should {
    val startingState = preProductionState(upliftRequestedByEmail).copy(updatedOn = now.minusHours(24L))

    "move back application to TESTING state" in {
      val resultState = startingState.toTesting(clock)

      resultState.name shouldBe State.TESTING
      resultState.requestedByEmailAddress shouldBe None
      resultState.verificationCode shouldBe None
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "move application to PRODUCTION state" in {
      val resultState = startingState.toProduction(clock)

      resultState.name shouldBe State.PRODUCTION
      resultState.requestedByEmailAddress shouldBe Some(upliftRequestedByEmail)
      resultState.verificationCode shouldBe defined
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "fail when application state is changed to PENDING_GATEKEEPER_APPROVAL" in {
      intercept[InvalidStateTransition](startingState.toPendingGatekeeperApproval(upliftRequestedByEmail, upliftRequestedByName, clock))
    }

    "fail when application state is changed to PENDING_REQUESTER_VERIFICATION" in {
      intercept[InvalidStateTransition](startingState.toPendingRequesterVerification(clock))
    }
    "fail when application state is changed to PRE_PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toPreProduction(clock))
    }
  }

  "state transition from PRODUCTION " should {
    val startingState = productionState(upliftRequestedByEmail).copy(updatedOn = FixedClock.now.minusHours(24L))
    "move back application to TESTING state" in {
      val resultState = startingState.toTesting(clockMinusHours(2L))

      resultState.name shouldBe State.TESTING
      resultState.requestedByEmailAddress shouldBe None
      resultState.verificationCode shouldBe None
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "move to DELETED state" in {
      val resultState = startingState.toDeleted(clockMinusHours(2L))

      resultState.name shouldBe State.DELETED
      resultState.isDeleted shouldBe true
      resultState.verificationCode shouldBe None
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "fail when application state is changed to PENDING_GATEKEEPER_APPROVAL" in {

      intercept[InvalidStateTransition](startingState.toPendingGatekeeperApproval(upliftRequestedByEmail, upliftRequestedByName, clock))
    }

    "fail when application state is changed to PENDING_REQUESTER_VERIFICATION" in {
      intercept[InvalidStateTransition](startingState.toPendingRequesterVerification(clock))
    }
    "fail when application state is changed to PRE_PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toPreProduction(clock))
    }

    "fail when application state is changed to PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toProduction(clock))
    }
  }
}
