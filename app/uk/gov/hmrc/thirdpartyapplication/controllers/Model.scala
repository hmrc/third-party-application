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

package uk.gov.hmrc.thirdpartyapplication.controllers

import play.api.libs.json.{JsObject, Json}
import play.api.libs.json.Json.JsValueWrapper
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.IpAllowlist
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.InvalidGrantLengthException

import java.time.LocalDateTime

case class ValidationRequest(clientId: ClientId, clientSecret: String)

case class ApplicationNameValidationRequest(applicationName: String, selfApplicationId: Option[ApplicationId])

case class ClientSecretRequest(actorEmailAddress: String)

case class ClientSecretRequestWithUserId(userId: UserId, actorEmailAddress: String)

case class DeleteClientSecretRequest(actorEmailAddress: String)

case class DeleteClientSecretsRequest(actorEmailAddress: String, secrets: List[String])

case class ApproveUpliftRequest(gatekeeperUserId: String)

case class RejectUpliftRequest(gatekeeperUserId: String, reason: String)

case class ResendVerificationRequest(gatekeeperUserId: String)

case class AddCollaboratorRequest(collaborator: Collaborator, isRegistered: Boolean, adminsToEmail: Set[String])

case class AddCollaboratorResponse(registeredUser: Boolean)

case class ScopeRequest(scopes: Set[String])

case class ScopeResponse(scopes: Set[String])

case class OverridesRequest(overrides: Set[OverrideFlag])

case class OverridesResponse(overrides: Set[OverrideFlag])

case class UpdateRateLimitTierRequest(rateLimitTier: String)

case class UpdateGrantLengthRequest(grantLengthInDays: Int)

object UpdateGrantLengthRequest {

  def toGrantLength(updateGrantLengthRequest: UpdateGrantLengthRequest): Int = {
    if (updateGrantLengthRequest.grantLengthInDays <= 0) {
      throw InvalidGrantLengthException("Grant Length in Days cannot be less than or equal to zero")
    }
    updateGrantLengthRequest.grantLengthInDays
  }
}

case class UpdateIpAllowlistRequest(required: Boolean, allowlist: Set[String])

object UpdateIpAllowlistRequest {

  def toIpAllowlist(updateIpAllowlistRequest: UpdateIpAllowlistRequest): IpAllowlist = {
    IpAllowlist(updateIpAllowlistRequest.required, updateIpAllowlistRequest.allowlist)
  }
}

case class DeleteApplicationRequest(gatekeeperUserId: String, requestedByEmailAddress: String)

case class DeleteSubordinateApplicationRequest(applicationId: String)

case class FixCollaboratorRequest(emailAddress: String, userId: UserId)

case class DeleteCollaboratorRequest(
    email: String,
    adminsToEmail: Set[String],
    notifyCollaborator: Boolean
  )

case class AddTermsOfUseAcceptanceRequest(name: String, emailAddress: String, acceptanceDate: LocalDateTime, submissionId: Submission.Id)

case class ConfirmSetupCompleteRequest(requesterEmailAddress: String)

object ErrorCode extends Enumeration {
  type ErrorCode = Value

  val INVALID_REQUEST_PAYLOAD      = Value("INVALID_REQUEST_PAYLOAD")
  val UNAUTHORIZED                 = Value("UNAUTHORIZED")
  val UNKNOWN_ERROR                = Value("UNKNOWN_ERROR")
  val APPLICATION_NOT_FOUND        = Value("APPLICATION_NOT_FOUND")
  val SCOPE_NOT_FOUND              = Value("SCOPE_NOT_FOUND")
  val INVALID_CREDENTIALS          = Value("INVALID_CREDENTIALS")
  val APPLICATION_ALREADY_EXISTS   = Value("APPLICATION_ALREADY_EXISTS")
  val SUBSCRIPTION_ALREADY_EXISTS  = Value("SUBSCRIPTION_ALREADY_EXISTS")
  val USER_ALREADY_EXISTS          = Value("USER_ALREADY_EXISTS")
  val APPLICATION_NEEDS_ADMIN      = Value("APPLICATION_NEEDS_ADMIN")
  val CLIENT_SECRET_LIMIT_EXCEEDED = Value("CLIENT_SECRET_LIMIT_EXCEEDED")
  val INVALID_STATE_TRANSITION     = Value("INVALID_STATE_TRANSITION")
  val SUBSCRIPTION_NOT_FOUND       = Value("SUBSCRIPTION_NOT_FOUND")
  val FORBIDDEN                    = Value("FORBIDDEN")
  val INVALID_IP_ALLOWLIST         = Value("INVALID_IP_ALLOWLIST")
  val INVALID_GRANT_LENGTH         = Value("INVALID_GRANT_LENGTH_IN_DAYS")
  val BAD_QUERY_PARAMETER          = Value("BAD_QUERY_PARAMETER")
}

object JsErrorResponse {

  def apply(errorCode: ErrorCode.Value, message: JsValueWrapper): JsObject =
    Json.obj(
      "code"    -> errorCode.toString,
      "message" -> message
    )
}
