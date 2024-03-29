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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationState, State}

object ApplicationStateExamples extends FixedClock {
  val testing: ApplicationState = ApplicationState(State.TESTING, None, updatedOn = instant)

  def pendingGatekeeperApproval(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, Some(requestedByEmail), Some(requestedByName), updatedOn = instant)

  def pendingRequesterVerification(requestedByEmail: String, requestedByName: String, verificationCode: String) =
    ApplicationState(State.PENDING_REQUESTER_VERIFICATION, Some(requestedByEmail), Some(requestedByName), Some(verificationCode), updatedOn = instant)

  def pendingResponsibleIndividualVerification(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION, Some(requestedByEmail), Some(requestedByName), updatedOn = instant)

  def preProduction(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PRE_PRODUCTION, Some(requestedByEmail), Some(requestedByName), updatedOn = instant)

  def production(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PRODUCTION, Some(requestedByEmail), Some(requestedByName), updatedOn = instant)

  def deleted(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.DELETED, Some(requestedByEmail), Some(requestedByName), updatedOn = instant)
}
