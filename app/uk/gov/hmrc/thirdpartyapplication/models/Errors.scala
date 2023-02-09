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

import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifier
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State

class UserAlreadyExists extends RuntimeException

class ApplicationNeedsAdmin extends RuntimeException

class ClientSecretsLimitExceeded extends RuntimeException

class InvalidStateTransition(invalidFrom: State, to: State, expectedFrom: State)
    extends RuntimeException(s"Transition to '$to' state requires the application to be in '$expectedFrom' state, but it was in '$invalidFrom'")

class InconsistentDataState(message: String) extends RuntimeException(message)

case class ApplicationAlreadyExists(applicationName: String) extends RuntimeException

case class SubscriptionAlreadyExistsException(name: String, api: ApiIdentifier)
    extends RuntimeException(s"""Application: '$name' is already Subscribed to API: ${api.asText(": ")}""")

case class FailedToSubscribeException(applicationName: String, api: ApiIdentifier)
    extends RuntimeException(s"""Failed to Subscribe API: ${api.asText(": ")} to Application: '$applicationName'""")

case class ScopeNotFoundException(scope: String) extends RuntimeException(s"Scope '$scope' not found")

case class InvalidIpAllowlistException(message: String) extends RuntimeException(message)

case class InvalidGrantLengthException(message: String) extends RuntimeException(message)
