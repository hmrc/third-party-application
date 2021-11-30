/*
 * Copyright 2021 HM Revenue & Customs
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
import uk.gov.hmrc.thirdpartyapplication.controllers.{ApplicationNameValidationRequest, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.utils.UtcMillisDateTimeFormatters
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationTokens

trait JsonFormatters extends UtcMillisDateTimeFormatters {

  // NOTE - these override the defaults in order to push dates in non-mongo format
  implicit val formatTermsOfUserAgreement = Json.format[TermsOfUseAgreement]
  implicit val formatCheckInformation = Json.format[CheckInformation]

  implicit val formatApplicationState = Json.format[ApplicationState]
  implicit val formatClientSecret = Json.format[ClientSecret]
  implicit val formatEnvironmentToken = Json.format[Token]
  implicit val formatApplicationTokens = Json.format[ApplicationTokens]

  // implicit val formatApplicationData = Json.format[ApplicationData]

  implicit val formatUpdateApplicationRequest = Json.format[UpdateApplicationRequest]
  implicit val formatApplicationResponse = Json.format[ApplicationResponse]
  implicit val formatExtendedApplicationResponse = Json.format[ExtendedApplicationResponse]
  implicit val formatPaginatedApplicationResponse = Json.format[PaginatedApplicationResponse]
  implicit val formatUpdateRateLimitTierRequest = Json.format[UpdateRateLimitTierRequest]
  implicit val formatUpdateIpAllowlistRequest = Json.format[UpdateIpAllowlistRequest]
  implicit val formatGrantLengthRequest = Json.format[UpdateGrantLengthRequest]
  implicit val formatApplicationWithHistory = Json.format[ApplicationWithHistory]
  implicit val formatClientSecretResponse = Json.format[ClientSecretResponse]
  implicit val formatApplicationTokensResponse = Json.format[ApplicationTokenResponse]


  implicit val formatValidationRequest = Json.format[ValidationRequest]
  implicit val formatApplicationNameValidationRequest = Json.format[ApplicationNameValidationRequest]
  implicit val formatClientSecretRequest = Json.format[ClientSecretRequest]
  implicit val formatApproveUpliftRequest = Json.format[ApproveUpliftRequest]
  implicit val formatRejectUpliftRequest = Json.format[RejectUpliftRequest]
  implicit val formatResendVerificationRequest = Json.format[ResendVerificationRequest]
  implicit val formatAddCollaboratorRequest = Json.format[AddCollaboratorRequest]
  implicit val formatAddCollaboratorResponse = Json.format[AddCollaboratorResponse]
  implicit val formatScopeRequest = Json.format[ScopeRequest]
  implicit val formatScopeResponse = Json.format[ScopeResponse]
  implicit val formatOverridesRequest = Json.format[OverridesRequest]
  implicit val formatOverridesResponse = Json.format[OverridesResponse]
  implicit val formatApplicationWithUpliftRequest = Json.format[ApplicationWithUpliftRequest]
  implicit val formatDeleteApplicationRequest = Json.format[DeleteApplicationRequest]
  implicit val formatDeleteClientSecretsRequest = Json.format[DeleteClientSecretsRequest]
  implicit val formatDeleteClientSecretRequest = Json.format[DeleteClientSecretRequest]
  implicit val formatFixCollaboratorRequest = Json.format[FixCollaboratorRequest]
  implicit val formatDeleteCollaboratorRequest = Json.format[DeleteCollaboratorRequest]

  implicit val createApplicationResponseWrites: Writes[CreateApplicationResponse] = (
    JsPath.write[ApplicationResponse] and (JsPath \ "totp").write[Option[TotpSecret]]
    )(unlift(CreateApplicationResponse.unapply))
}


object JsonFormatters extends JsonFormatters

