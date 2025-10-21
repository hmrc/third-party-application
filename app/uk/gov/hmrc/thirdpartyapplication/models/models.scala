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

package uk.gov.hmrc.thirdpartyapplication.models

import java.time.Instant

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId

case class ValidationRequest(clientId: ClientId, clientSecret: String)

case class OldApplicationNameValidationRequest(applicationName: String, selfApplicationId: Option[ApplicationId])

case class ApproveUpliftRequest(gatekeeperUserId: String)

case class RejectUpliftRequest(gatekeeperUserId: String, reason: String)

case class ResendVerificationRequest(gatekeeperUserId: String)

case class ScopeRequest(scopes: Set[String])

case class ScopeResponse(scopes: Set[String])

case class OverridesRequest(overrides: Set[OverrideFlag])

case class OverridesResponse(overrides: Set[OverrideFlag])

case class UpdateIpAllowlistRequest(required: Boolean, allowlist: Set[String])

object UpdateIpAllowlistRequest {

  def toIpAllowlist(updateIpAllowlistRequest: UpdateIpAllowlistRequest): IpAllowlist = {
    IpAllowlist(updateIpAllowlistRequest.required, updateIpAllowlistRequest.allowlist)
  }
}

case class DeleteApplicationRequest(gatekeeperUserId: String, requestedByEmailAddress: LaxEmailAddress)

case class DeleteSubordinateApplicationRequest(applicationId: String)

case class FixCollaboratorRequest(emailAddress: String, userId: UserId)

case class AddTermsOfUseAcceptanceRequest(name: String, emailAddress: String, acceptanceDate: Instant, submissionId: SubmissionId)
case class ConfirmSetupCompleteRequest(requesterEmailAddress: LaxEmailAddress)

case class CollaboratorUserIds(userIds: List[UserId])
