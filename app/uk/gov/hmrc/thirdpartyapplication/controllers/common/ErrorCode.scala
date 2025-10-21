/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers.common

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
  val BAD_QUERY_PARAMETER          = Value("BAD_QUERY_PARAMETER")
  val FAILED_TO_SUBSCRIBE          = Value("FAILED_TO_SUBSCRIBE")
}
