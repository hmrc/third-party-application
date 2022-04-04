/*
 * Copyright 2022 HM Revenue & Customs
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

import uk.gov.hmrc.time.DateTimeUtils
import org.joda.time.DateTime
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.{State, _}
import uk.gov.hmrc.thirdpartyapplication.models.InvalidStateTransition
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import java.nio.charset.StandardCharsets
import java.{util => ju}

case class ApplicationState(
  name: State = TESTING,
  requestedByEmailAddress: Option[String] = None,
  verificationCode: Option[String] = None,
  updatedOn: DateTime = DateTimeUtils.now
) {

  final def requireState(requirement: State, transitionTo: State): Unit = {
    if (name != requirement) {
      throw new InvalidStateTransition(expectedFrom = requirement, invalidFrom = name, to = transitionTo)
    }
  }

  def isInTesting = name == State.TESTING
  def isPendingGatekeeperApproval = name == State.PENDING_GATEKEEPER_APPROVAL
  def isPendingRequesterVerification = name == State.PENDING_REQUESTER_VERIFICATION
  def isInPreProductionOrProduction = name == State.PRE_PRODUCTION || name == State.PRODUCTION

  def toProduction = {
    requireState(requirement = State.PRE_PRODUCTION, transitionTo = PRODUCTION)
    copy(name = PRODUCTION, updatedOn = DateTimeUtils.now)
  }

  def toPreProduction = {
    requireState(requirement = State.PENDING_REQUESTER_VERIFICATION, transitionTo = PRE_PRODUCTION)
    copy(name = PRE_PRODUCTION, updatedOn = DateTimeUtils.now)
  }

  def toTesting = copy(name = TESTING, requestedByEmailAddress = None, verificationCode = None, updatedOn = DateTimeUtils.now)

  def toPendingGatekeeperApproval(requestedByEmailAddress: String) = {
    requireState(requirement = TESTING, transitionTo = State.PENDING_GATEKEEPER_APPROVAL)

    copy(name = State.PENDING_GATEKEEPER_APPROVAL,
      updatedOn = DateTimeUtils.now,
      requestedByEmailAddress = Some(requestedByEmailAddress))
  }

  def toPendingRequesterVerification = {
    requireState(requirement = State.PENDING_GATEKEEPER_APPROVAL, transitionTo = State.PENDING_REQUESTER_VERIFICATION)

    def verificationCode(input: String = ju.UUID.randomUUID().toString): String = {
      def urlSafe(encoded: String) = encoded.replace("=", "").replace("/", "_").replace("+", "-")

      val digest = MessageDigest.getInstance("SHA-256")
      urlSafe(new String(Base64.encodeBase64(digest.digest(input.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8))
    }
    copy(name = State.PENDING_REQUESTER_VERIFICATION, verificationCode = Some(verificationCode()), updatedOn = DateTimeUtils.now)
  }
}

object ApplicationState {
  import play.api.libs.json.Json
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeFormats
 
  implicit val formatApplicationState = Json.format[ApplicationState]

  val testing = ApplicationState(State.TESTING, None)

  def pendingGatekeeperApproval(requestedBy: String) =
    ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, Some(requestedBy))

  def pendingRequesterVerification(requestedBy: String, verificationCode: String) =
    ApplicationState(State.PENDING_REQUESTER_VERIFICATION, Some(requestedBy), Some(verificationCode))

  def preProduction(requestedBy: String, verificationCode: String) =
    ApplicationState(State.PRE_PRODUCTION, Some(requestedBy), Some(verificationCode))

  def production(requestedBy: String, verificationCode: String) =
    ApplicationState(State.PRODUCTION, Some(requestedBy), Some(verificationCode))
}