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

package uk.gov.hmrc.thirdpartyapplication

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationState, State}

trait ApplicationStateUtil extends FixedClock {
  val generatedVerificationCode: String = "verificationCode"

  def testingState() = ApplicationState(name = State.TESTING, updatedOn = now)

  def preProductionState(requestedBy: String) = ApplicationState(
    name = State.PRE_PRODUCTION,
    requestedByEmailAddress = Some(requestedBy),
    verificationCode = Some(generatedVerificationCode),
    updatedOn = now
  )

  def productionState(requestedBy: String) = ApplicationState(
    name = State.PRODUCTION,
    requestedByEmailAddress = Some(requestedBy),
    verificationCode = Some(generatedVerificationCode),
    updatedOn = now
  )

  def pendingRequesterVerificationState(requestedBy: String) = ApplicationState(
    name = State.PENDING_REQUESTER_VERIFICATION,
    requestedByEmailAddress = Some(requestedBy),
    verificationCode = Some(generatedVerificationCode),
    updatedOn = now
  )

  def pendingGatekeeperApprovalState(requestedBy: String) = ApplicationState(
    name = State.PENDING_GATEKEEPER_APPROVAL,
    requestedByEmailAddress = Some(requestedBy),
    requestedByName = Some(requestedBy),
    verificationCode = None,
    updatedOn = now
  )

  def pendingResponsibleIndividualVerificationState(requestedBy: String, requestedByEmail: String) = ApplicationState(
    name = State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
    requestedByEmailAddress = Some(requestedByEmail),
    requestedByName = Some(requestedBy),
    verificationCode = Some(generatedVerificationCode),
    updatedOn = now
  )

  def deletedState(requestedBy: String) = ApplicationState(
    name = State.DELETED,
    requestedByEmailAddress = Some(requestedBy),
    requestedByName = Some(requestedBy),
    verificationCode = None,
    updatedOn = now
  )
}
