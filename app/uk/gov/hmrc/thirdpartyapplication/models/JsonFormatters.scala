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

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.*
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.*
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.*
import uk.gov.hmrc.thirdpartyapplication.domain.utils.UtcMillisDateTimeFormatters
import uk.gov.hmrc.thirdpartyapplication.models.*

trait JsonFormatters extends UtcMillisDateTimeFormatters {
  given formatValidationRequest: OFormat[ValidationRequest]                                  = Json.format[ValidationRequest]
  given formatApplicationNameValidationRequest: OFormat[OldApplicationNameValidationRequest] = Json.format[OldApplicationNameValidationRequest]
  given formatApproveUpliftRequest: OFormat[ApproveUpliftRequest]                            = Json.format[ApproveUpliftRequest]
  given formatRejectUpliftRequest: OFormat[RejectUpliftRequest]                              = Json.format[RejectUpliftRequest]
  given formatResendVerificationRequest: OFormat[ResendVerificationRequest]                  = Json.format[ResendVerificationRequest]
  given formatAddTermsOfUseAcceptanceRequest: OFormat[AddTermsOfUseAcceptanceRequest]        = Json.format[AddTermsOfUseAcceptanceRequest]
  given formatConfirmSetupCompleteRequest: OFormat[ConfirmSetupCompleteRequest]              = Json.format[ConfirmSetupCompleteRequest]
  given formatCollaboratorUserIds: OFormat[CollaboratorUserIds]                              = Json.format[CollaboratorUserIds]
  given formatScopeRequest: OFormat[ScopeRequest]                                            = Json.format[ScopeRequest]
  given formatScopeResponse: OFormat[ScopeResponse]                                          = Json.format[ScopeResponse]
  given formatOverridesRequest: OFormat[OverridesRequest]                                    = Json.format[OverridesRequest]
  given formatOverridesResponse: OFormat[OverridesResponse]                                  = Json.format[OverridesResponse]
  given formatApplicationWithUpliftRequest: OFormat[ApplicationWithUpliftRequest]            = Json.format[ApplicationWithUpliftRequest]
  given formatDeleteApplicationRequest: OFormat[DeleteApplicationRequest]                    = Json.format[DeleteApplicationRequest]
  given formatFixCollaboratorRequest: OFormat[FixCollaboratorRequest]                        = Json.format[FixCollaboratorRequest]

  given createApplicationResponseWrites: OWrites[CreateApplicationResponse] = (
    JsPath.write[ApplicationWithCollaborators] and (JsPath \ "totp").writeNullable[CreateApplicationResponse.TotpSecret]
  )(car => (car.application, car.totp))
}

object JsonFormatters extends JsonFormatters
