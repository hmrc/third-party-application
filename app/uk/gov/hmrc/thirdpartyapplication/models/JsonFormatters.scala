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

import play.api.libs.functional.syntax._
import play.api.libs.json._

import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.controllers.{ApplicationNameValidationRequest, _}
import uk.gov.hmrc.thirdpartyapplication.domain.utils.UtcMillisDateTimeFormatters

trait JsonFormatters extends UtcMillisDateTimeFormatters {

  // NOTE - these override the defaults in order to push dates in non-mongo format
  implicit val formatTermsOfUserAgreement: OFormat[TermsOfUseAgreement]        = Json.format[TermsOfUseAgreement]
  implicit val formatTermsOfUseAcceptance: OFormat[TermsOfUseAcceptance]       = Json.format[TermsOfUseAcceptance]
  implicit val formatImportantSubmissionData: OFormat[ImportantSubmissionData] = Json.format[ImportantSubmissionData]
  implicit val formatStandard: OFormat[Access.Standard]                        = Json.format[Access.Standard]
  implicit val formatPrivileged: OFormat[Access.Privileged]                    = Json.format[Access.Privileged]
  implicit val formatRopc: OFormat[Access.Ropc]                                = Json.format[Access.Ropc]
  import uk.gov.hmrc.play.json.Union

  implicit val formatAccess: OFormat[Access] = Union.from[Access]("accessType")
    .and[Access.Standard](AccessType.STANDARD.toString)
    .and[Access.Privileged](AccessType.PRIVILEGED.toString)
    .and[Access.Ropc](AccessType.ROPC.toString)
    .format

  implicit val formatCheckInformation: OFormat[CheckInformation] = Json.format[CheckInformation]

  implicit val formatApplicationState: OFormat[ApplicationState] = Json.format[ApplicationState]

  implicit val formatUpdateApplicationRequest: OFormat[UpdateApplicationRequest]         = Json.format[UpdateApplicationRequest]
  implicit val formatApplicationResponse: OFormat[Application]                           = Json.format[Application]
  implicit val formatExtendedApplicationResponse: OFormat[ExtendedApplicationResponse]   = Json.format[ExtendedApplicationResponse]
  implicit val formatPaginatedApplicationResponse: OFormat[PaginatedApplicationResponse] = Json.format[PaginatedApplicationResponse]
  implicit val formatUpdateIpAllowlistRequest: OFormat[UpdateIpAllowlistRequest]         = Json.format[UpdateIpAllowlistRequest]
  implicit val formatApplicationWithHistory: OFormat[ApplicationWithHistoryResponse]     = Json.format[ApplicationWithHistoryResponse]
  implicit val formatClientSecretResponse: OFormat[ClientSecretResponse]                 = Json.format[ClientSecretResponse]
  implicit val formatApplicationTokensResponse: OFormat[ApplicationTokenResponse]        = Json.format[ApplicationTokenResponse]

  implicit val formatValidationRequest: OFormat[ValidationRequest]                               = Json.format[ValidationRequest]
  implicit val formatApplicationNameValidationRequest: OFormat[ApplicationNameValidationRequest] = Json.format[ApplicationNameValidationRequest]
  implicit val formatApproveUpliftRequest: OFormat[ApproveUpliftRequest]                         = Json.format[ApproveUpliftRequest]
  implicit val formatRejectUpliftRequest: OFormat[RejectUpliftRequest]                           = Json.format[RejectUpliftRequest]
  implicit val formatResendVerificationRequest: OFormat[ResendVerificationRequest]               = Json.format[ResendVerificationRequest]
  implicit val formatAddTermsOfUseAcceptanceRequest: OFormat[AddTermsOfUseAcceptanceRequest]     = Json.format[AddTermsOfUseAcceptanceRequest]
  implicit val formatConfirmSetupCompleteRequest: OFormat[ConfirmSetupCompleteRequest]           = Json.format[ConfirmSetupCompleteRequest]
  implicit val formatScopeRequest: OFormat[ScopeRequest]                                         = Json.format[ScopeRequest]
  implicit val formatScopeResponse: OFormat[ScopeResponse]                                       = Json.format[ScopeResponse]
  implicit val formatOverridesRequest: OFormat[OverridesRequest]                                 = Json.format[OverridesRequest]
  implicit val formatOverridesResponse: OFormat[OverridesResponse]                               = Json.format[OverridesResponse]
  implicit val formatApplicationWithUpliftRequest: OFormat[ApplicationWithUpliftRequest]         = Json.format[ApplicationWithUpliftRequest]
  implicit val formatDeleteApplicationRequest: OFormat[DeleteApplicationRequest]                 = Json.format[DeleteApplicationRequest]
  implicit val formatFixCollaboratorRequest: OFormat[FixCollaboratorRequest]                     = Json.format[FixCollaboratorRequest]

  implicit val createApplicationResponseWrites: OWrites[CreateApplicationResponse] = (
    JsPath.write[Application] and (JsPath \ "totp").write[Option[CreateApplicationResponse.TotpSecret]]
  )(unlift(CreateApplicationResponse.unapply))
}

object JsonFormatters extends JsonFormatters
