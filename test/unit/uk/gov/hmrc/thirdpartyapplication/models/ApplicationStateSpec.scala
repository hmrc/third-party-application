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

package unit.uk.gov.hmrc.thirdpartyapplication.models

import org.joda.time.DateTimeUtils
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.test.UnitSpec
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil

class ApplicationStateSpec extends UnitSpec with ApplicationStateUtil with BeforeAndAfterEach {

  override protected def beforeEach(): Unit = {
    DateTimeUtils.setCurrentMillisSystem()
  }

  val upliftRequestedBy = "requester@example.com"

  "state transition from TESTING " should {
    val startingState = testingState()
    "move application to PENDING_GATEKEEPER_APPROVAL state" in {
      val resultState = startingState.toPendingGatekeeperApproval(upliftRequestedBy)

      resultState.name shouldBe State.PENDING_GATEKEEPER_APPROVAL
      resultState.requestedByEmailAddress shouldBe Some(upliftRequestedBy)
      resultState.verificationCode shouldBe None
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "fail when application state is changed to PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toProduction)
    }

    "fail when application state is changed to PENDING_REQUESTER_VERIFICATION" in {
      intercept[InvalidStateTransition](startingState.toPendingRequesterVerification)
    }
  }

  "state transition from PENDING_GATEKEEPER_APPROVAL " should {
    val startingState = pendingGatekeeperApprovalState(upliftRequestedBy)
    "move application to PENDING_REQUESTER_VERIFICATION state" in {
      val resultState = startingState.toPendingRequesterVerification

      resultState.name shouldBe State.PENDING_REQUESTER_VERIFICATION
      resultState.requestedByEmailAddress shouldBe Some(upliftRequestedBy)
      resultState.verificationCode shouldBe defined
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }

    "fail when application state is changed to PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toProduction)
    }

    "fail when application state is changed to PENDING_GATEKEEPER_APPROVAL" in {
      intercept[InvalidStateTransition](startingState.toPendingGatekeeperApproval(upliftRequestedBy))
    }
  }


  "state transition from PENDING_REQUESTER_VERIFICATION " should {
    val startingState = pendingRequesterVerificationState(upliftRequestedBy)
    "move application to PRODUCTION state" in {
      val resultState = startingState.toProduction

      resultState.name shouldBe State.PRODUCTION
      resultState.requestedByEmailAddress shouldBe Some(upliftRequestedBy)
      resultState.verificationCode shouldBe defined
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }
    "fail when application state is changed to PENDING_GATEKEEPER_APPROVAL" in {
      intercept[InvalidStateTransition](startingState.toPendingGatekeeperApproval(upliftRequestedBy))
    }

    "fail when application state is changed to PENDING_REQUESTER_VERIFICATION" in {
      intercept[InvalidStateTransition](startingState.toPendingRequesterVerification)
    }
  }

  "state transition from PRODUCTION " should {
    val startingState = productionState(upliftRequestedBy)
    "move back application to TESTING state" in {
      val resultState = startingState.toTesting

      resultState.name shouldBe State.TESTING
      resultState.requestedByEmailAddress shouldBe None
      resultState.verificationCode shouldBe None
      resultState.updatedOn.isAfter(startingState.updatedOn) shouldBe true
    }
    "fail when application state is changed to PENDING_GATEKEEPER_APPROVAL" in {
      intercept[InvalidStateTransition](startingState.toPendingGatekeeperApproval(upliftRequestedBy))
    }

    "fail when application state is changed to PENDING_REQUESTER_VERIFICATION" in {
      intercept[InvalidStateTransition](startingState.toPendingRequesterVerification)
    }

    "fail when application state is changed to PRODUCTION" in {
      intercept[InvalidStateTransition](startingState.toProduction)
    }
  }
}
