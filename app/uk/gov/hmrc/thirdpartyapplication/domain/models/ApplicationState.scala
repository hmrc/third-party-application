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

import play.api.libs.json.{Format, OFormat}
import play.shaded.oauth.org.apache.commons.codec.binary.Base64
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.{State, _}
import uk.gov.hmrc.thirdpartyapplication.models.InvalidStateTransition

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Clock, LocalDateTime, ZoneOffset}
import java.{util => ju}

case class ApplicationState(
    name: State = TESTING,
    requestedByEmailAddress: Option[String] = None,
    requestedByName: Option[String] = None,
    verificationCode: Option[String] = None,
    updatedOn: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
  ) {

  final def requireState(requirement: State, transitionTo: State): Unit = {
    if (name != requirement) {
      throw new InvalidStateTransition(expectedFrom = requirement, invalidFrom = name, to = transitionTo)
    }
  }

  def isInTesting                                                      = name == State.TESTING
  def isPendingResponsibleIndividualVerification                       = name == State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
  def isPendingGatekeeperApproval                                      = name == State.PENDING_GATEKEEPER_APPROVAL
  def isPendingRequesterVerification                                   = name == State.PENDING_REQUESTER_VERIFICATION
  def isInPreProductionOrProduction                                    = name == State.PRE_PRODUCTION || name == State.PRODUCTION
  def isInPendingGatekeeperApprovalOrResponsibleIndividualVerification = name == State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION || name == State.PENDING_GATEKEEPER_APPROVAL
  def isDeleted                                                        = name == State.DELETED

  def toProduction(clock: Clock) = {
    requireState(requirement = State.PRE_PRODUCTION, transitionTo = PRODUCTION)
    copy(name = PRODUCTION, updatedOn = LocalDateTime.now(clock))
  }

  def toPreProduction(clock: Clock) = {
    requireState(requirement = State.PENDING_REQUESTER_VERIFICATION, transitionTo = PRE_PRODUCTION)
    copy(name = PRE_PRODUCTION, updatedOn = LocalDateTime.now(clock))
  }

  def toTesting(clock: Clock) = copy(name = TESTING, requestedByEmailAddress = None, requestedByName = None, verificationCode = None, updatedOn = LocalDateTime.now(clock))

  def toPendingGatekeeperApproval(requestedByEmailAddress: String, requestedByName: String, clock: Clock) = {
    requireState(requirement = TESTING, transitionTo = State.PENDING_GATEKEEPER_APPROVAL)

    copy(
      name = State.PENDING_GATEKEEPER_APPROVAL, 
      updatedOn = LocalDateTime.now(clock), 
      requestedByEmailAddress = Some(requestedByEmailAddress),
      requestedByName = Some(requestedByName)
    )
  }

  def toPendingResponsibleIndividualVerification(requestedByEmailAddress: String, requestedByName: String, clock: Clock) = {
    requireState(requirement = TESTING, transitionTo = State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION)

    copy(
      name = State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
      updatedOn = LocalDateTime.now(clock),
      requestedByEmailAddress = Some(requestedByEmailAddress),
      requestedByName = Some(requestedByName)
    )
  }

  def toPendingRequesterVerification(clock: Clock) = {
    requireState(requirement = State.PENDING_GATEKEEPER_APPROVAL, transitionTo = State.PENDING_REQUESTER_VERIFICATION)

    def verificationCode(input: String = ju.UUID.randomUUID().toString): String = {
      def urlSafe(encoded: String) = encoded.replace("=", "").replace("/", "_").replace("+", "-")

      val digest = MessageDigest.getInstance("SHA-256")
      urlSafe(new String(Base64.encodeBase64(digest.digest(input.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8))
    }
    copy(name = State.PENDING_REQUESTER_VERIFICATION, verificationCode = Some(verificationCode()), updatedOn = LocalDateTime.now(clock))
  }

  def toDeleted(clock: Clock) = copy(name = DELETED, verificationCode = None, updatedOn = LocalDateTime.now(clock))
}

object ApplicationState {
  import play.api.libs.json.Json
  implicit val dateTimeFormats: Format[LocalDateTime]            = MongoJavatimeFormats.localDateTimeFormat
  implicit val formatApplicationState: OFormat[ApplicationState] = Json.format[ApplicationState]

  val testing: ApplicationState = ApplicationState(State.TESTING, None)

  def pendingGatekeeperApproval(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, Some(requestedByEmail), Some(requestedByName))

  def pendingRequesterVerification(requestedByEmail: String, requestedByName: String, verificationCode: String) =
    ApplicationState(State.PENDING_REQUESTER_VERIFICATION, Some(requestedByEmail), Some(requestedByName), Some(verificationCode))

  def pendingResponsibleIndividualVerification(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION, Some(requestedByEmail), Some(requestedByName))

  def preProduction(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PRE_PRODUCTION, Some(requestedByEmail), Some(requestedByName))

  def production(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PRODUCTION, Some(requestedByEmail), Some(requestedByName))

  def deleted(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.DELETED, Some(requestedByEmail), Some(requestedByName))
}
