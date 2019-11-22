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

package uk.gov.hmrc.thirdpartyapplication.models

import uk.gov.hmrc.thirdpartyapplication.models.State.State

case class InvalidUpliftVerificationCode(code: String) extends RuntimeException(s"Invalid verification code '$code'")

class UserAlreadyExists extends RuntimeException

class ApplicationNeedsAdmin extends RuntimeException

class ClientSecretsLimitExceeded extends RuntimeException

class InvalidStateTransition(invalidFrom: State, to: State, expectedFrom: State)
  extends RuntimeException(s"Transition to '$to' state requires the application to be in '$expectedFrom' state, but it was in '$invalidFrom'")

class InconsistentDataState(message: String) extends RuntimeException(message)

case class ApplicationAlreadyExists(applicationName: String) extends RuntimeException

case class SubscriptionAlreadyExistsException(name: String, api: APIIdentifier)
  extends RuntimeException(s"Application: '$name' is already Subscribed to API: ${api.context}: ${api.version}")

case class ScopeNotFoundException(scope: String) extends RuntimeException(s"Scope '$scope' not found")

case class OverrideNotFoundException(anOverride: String) extends RuntimeException(s"Override '$anOverride' not found")

case class InvalidIpWhitelistException(message: String) extends RuntimeException(message)
