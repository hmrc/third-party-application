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

package uk.gov.hmrc.thirdpartyapplication.controllers

import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models.{Collaborator, OverrideFlag}

case class ValidationRequest(clientId: String, clientSecret: String)

case class ClientSecretRequest(name: String)

case class DeleteClientSecretsRequest(secrets: Seq[String])

case class UpliftRequest(applicationName: String, requestedByEmailAddress: String)

case class ApproveUpliftRequest(gatekeeperUserId: String)

case class RejectUpliftRequest(gatekeeperUserId: String, reason: String)

case class ResendVerificationRequest(gatekeeperUserId: String)

case class AddCollaboratorRequest(adminEmail: String, collaborator: Collaborator, isRegistered: Boolean, adminsToEmail: Set[String])

case class AddCollaboratorResponse(registeredUser: Boolean)

case class ScopeRequest(scopes: Set[String])

case class ScopeResponse(scopes: Set[String])

case class OverridesRequest(overrides: Set[OverrideFlag])

case class OverridesResponse(overrides: Set[OverrideFlag])

case class UpdateRateLimitTierRequest(rateLimitTier: String)

case class DeleteApplicationRequest(gatekeeperUserId: String, requestedByEmailAddress: String)

object ErrorCode extends Enumeration {
  type ErrorCode = Value

  val INVALID_REQUEST_PAYLOAD = Value("INVALID_REQUEST_PAYLOAD")
  val UNAUTHORIZED = Value("UNAUTHORIZED")
  val UNKNOWN_ERROR = Value("UNKNOWN_ERROR")
  val APPLICATION_NOT_FOUND = Value("APPLICATION_NOT_FOUND")
  val SCOPE_NOT_FOUND = Value("SCOPE_NOT_FOUND")
  val INVALID_CREDENTIALS = Value("INVALID_CREDENTIALS")
  val APPLICATION_ALREADY_EXISTS = Value("APPLICATION_ALREADY_EXISTS")
  val SUBSCRIPTION_ALREADY_EXISTS = Value("SUBSCRIPTION_ALREADY_EXISTS")
  val USER_ALREADY_EXISTS = Value("USER_ALREADY_EXISTS")
  val APPLICATION_NEEDS_ADMIN = Value("APPLICATION_NEEDS_ADMIN")
  val CLIENT_SECRET_LIMIT_EXCEEDED = Value("CLIENT_SECRET_LIMIT_EXCEEDED")
  val INVALID_STATE_TRANSITION = Value("INVALID_STATE_TRANSITION")
  val SUBSCRIPTION_NOT_FOUND = Value("SUBSCRIPTION_NOT_FOUND")
}

object JsErrorResponse {
  def apply(errorCode: ErrorCode.Value, message: JsValueWrapper): JsObject =
    Json.obj(
      "code" -> errorCode.toString,
      "message" -> message
    )
}

