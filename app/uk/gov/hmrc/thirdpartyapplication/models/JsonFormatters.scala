/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.UUID

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.play.json.Union
import uk.gov.hmrc.thirdpartyapplication.connector.{FetchUsersByEmailAddressesRequest, UpdateApplicationUsagePlanRequest}
import uk.gov.hmrc.thirdpartyapplication.controllers.{ApplicationNameValidationRequest, _}
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.{PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.thirdpartyapplication.models.OverrideType._
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}

import scala.language.implicitConversions

object JsonFormatters {
  implicit val formatSubscribersResponse = Json.format[SubscribersResponse]
  implicit val formatRole = EnumJson.enumFormat(Role)
  implicit val formatEnvironment = EnumJson.enumFormat(Environment)
  implicit val formatAccessType = EnumJson.enumFormat(AccessType)
  implicit val formatState = EnumJson.enumFormat(State)
  implicit val formatRateLimitTier = EnumJson.enumFormat(RateLimitTier)

  private implicit val formatGrantWithoutConsent = Json.format[GrantWithoutConsent]
  private implicit val formatPersistLogin = Format[PersistLogin](
    Reads { _ => JsSuccess(PersistLogin()) },
    Writes { _ => Json.obj() })
  private implicit val formatSuppressIvForAgents = Json.format[SuppressIvForAgents]
  private implicit val formatSuppressIvForOrganisations = Json.format[SuppressIvForOrganisations]
  private implicit val formatSuppressIvForIndividuals = Json.format[SuppressIvForIndividuals]

  implicit val formatOverride = Union.from[OverrideFlag]("overrideType")
    .and[GrantWithoutConsent](GRANT_WITHOUT_TAXPAYER_CONSENT.toString)
    .and[PersistLogin](PERSIST_LOGIN_AFTER_GRANT.toString)
    .and[SuppressIvForAgents](SUPPRESS_IV_FOR_AGENTS.toString)
    .and[SuppressIvForOrganisations](SUPPRESS_IV_FOR_ORGANISATIONS.toString)
    .and[SuppressIvForIndividuals](SUPPRESS_IV_FOR_INDIVIDUALS.toString)
    .format

  implicit val formatTotp = Json.format[Totp]
  implicit val formatTotpIds = Json.format[TotpIds]
  implicit val formatTotpSecrets = Json.format[TotpSecrets]

  private implicit val formatStandard = Json.format[Standard]
  private implicit val formatPrivileged = Json.format[Privileged]
  private implicit val formatRopc = Json.format[Ropc]

  implicit val formatAccess = Union.from[Access]("accessType")
    .and[Standard](STANDARD.toString)
    .and[Privileged](PRIVILEGED.toString)
    .and[Ropc](ROPC.toString)
    .format

  implicit val formatTermsOfUseAgreement = Json.format[TermsOfUseAgreement]

  val checkInformationReads: Reads[CheckInformation] = (
    (JsPath \ "contactDetails").readNullable[ContactDetails] and
      (JsPath \ "confirmedName").read[Boolean] and
      ((JsPath \ "apiSubscriptionsConfirmed").read[Boolean] or Reads.pure(false)) and
      (JsPath \ "providedPrivacyPolicyURL").read[Boolean] and
      (JsPath \ "providedTermsAndConditionsURL").read[Boolean] and
      (JsPath \ "applicationDetails").readNullable[String] and
      ((JsPath \ "teamConfirmed").read[Boolean] or Reads.pure(false)) and
      ((JsPath \ "termsOfUseAgreements").read[List[TermsOfUseAgreement]] or Reads.pure(List.empty[TermsOfUseAgreement]))
    )(CheckInformation.apply _)

  implicit val checkInformationFormat = {
    Format(checkInformationReads, Json.writes[CheckInformation])
  }

  implicit val formatAPIStatus = APIStatusJson.apiStatusFormat(ApiStatus)
  implicit val formatAPIAccessType = EnumJson.enumFormat(APIAccessType)
  implicit val formatAPIAccess = Json.format[ApiAccess]
  implicit val formatAPIVersion = Json.format[ApiVersion]
  implicit val formatVersionSubscription = Json.format[VersionSubscription]
  implicit val formatApiSubscription = Json.format[ApiSubscription]

  val apiDefinitionReads: Reads[ApiDefinition] = (
    (JsPath \ "serviceName").read[String] and
      (JsPath \ "name").read[String] and
      (JsPath \ "context").read[String] and
      (JsPath \ "versions").read[List[ApiVersion]] and
      (JsPath \ "isTestSupport").readNullable[Boolean]
    ) (ApiDefinition.apply _)

  implicit val formatAPIDefinition = {
    Format(apiDefinitionReads, Json.writes[ApiDefinition])
  }

  implicit val formatApplicationState = Json.format[ApplicationState]
  implicit val formatApiIdentifier = Json.format[APIIdentifier]
  implicit val formatCollaborator = Json.format[Collaborator]
  implicit val formatClientSecret = Json.format[ClientSecret]
  implicit val formatEnvironmentToken = Json.format[EnvironmentToken]
  implicit val formatApplicationTokens = Json.format[ApplicationTokens]
  implicit val formatSubscriptionData = Json.format[SubscriptionData]

  implicit val formatApplicationData = Json.format[ApplicationData]

  implicit val formatCreateApplicationRequest = Json.format[CreateApplicationRequest]
  implicit val formatUpdateApplicationRequest = Json.format[UpdateApplicationRequest]
  implicit val formatApplicationResponse = Json.format[ApplicationResponse]
  implicit val formatExtendedApplicationResponse = Json.format[ExtendedApplicationResponse]
  implicit val formatPaginatedApplicationResponse = Json.format[PaginatedApplicationResponse]
  implicit val formatUpdateRateLimitTierRequest = Json.format[UpdateRateLimitTierRequest]
  implicit val formatUpdateIpWhitelistRequest = Json.format[UpdateIpWhitelistRequest]
  implicit val formatApplicationWithHistory = Json.format[ApplicationWithHistory]
  implicit val formatEnvironmentTokenResponse = Json.format[EnvironmentTokenResponse]
  implicit val formatApplicationTokensResponse = Json.format[ApplicationTokensResponse]
  implicit val formatWso2Credentials = Json.format[Wso2Credentials]

  implicit val formatValidationRequest = Json.format[ValidationRequest]
  implicit val formatApplicationNameValidationRequest = Json.format[ApplicationNameValidationRequest]
  implicit val formatClientSecretRequest = Json.format[ClientSecretRequest]
  implicit val formatUpliftRequest = Json.format[UpliftRequest]
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
  implicit val formatDeleteClientSecretRequest = Json.format[DeleteClientSecretsRequest]
  implicit val formatUpdateUsagePlanRequest = Json.format[UpdateApplicationUsagePlanRequest]
  implicit val formatFetchUsersByEmailAddressesRequest = Json.format[FetchUsersByEmailAddressesRequest]

  implicit val createApplicationResponseWrites: Writes[CreateApplicationResponse] = (
    JsPath.write[ApplicationResponse] and (JsPath \ "totp").write[Option[TotpSecrets]]
    )(unlift(CreateApplicationResponse.unapply))
}


object MongoFormat {
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  implicit val formatTermsOfUseAgreement = Json.format[TermsOfUseAgreement]

  val checkInformationReads: Reads[CheckInformation] = (
    (JsPath \ "contactDetails").readNullable[ContactDetails] and
      (JsPath \ "confirmedName").read[Boolean] and
      ((JsPath \ "apiSubscriptionsConfirmed").read[Boolean] or Reads.pure(false)) and
      (JsPath \ "providedPrivacyPolicyURL").read[Boolean] and
      (JsPath \ "providedTermsAndConditionsURL").read[Boolean] and
      (JsPath \ "applicationDetails").readNullable[String] and
      ((JsPath \ "teamConfirmed").read[Boolean] or Reads.pure(false)) and
      ((JsPath \ "termsOfUseAgreements").read[List[TermsOfUseAgreement]] or Reads.pure(List.empty[TermsOfUseAgreement]))
    )(CheckInformation.apply _)

  implicit val checkInformationFormat = {
    Format(checkInformationReads, Json.writes[CheckInformation])
  }

  implicit val formatAccessType = JsonFormatters.formatAccessType
  implicit val formatRole = JsonFormatters.formatRole
  implicit val formatState = JsonFormatters.formatState
  implicit val formatRateLimitTier = JsonFormatters.formatRateLimitTier
  implicit val formatAccess = JsonFormatters.formatAccess
  implicit val formatApplicationState = Json.format[ApplicationState]
  implicit val formatCollaborator = Json.format[Collaborator]
  implicit val formatClientSecret = Json.format[ClientSecret]
  implicit val formatEnvironmentToken = Json.format[EnvironmentToken]
  implicit val formatApplicationTokens = Json.format[ApplicationTokens]
  implicit val formatApiIdentifier = Json.format[APIIdentifier]
  implicit val formatSubscriptionData = Json.format[SubscriptionData]

  val applicationDataReads: Reads[ApplicationData] = (
    (JsPath \ "id").read[UUID] and
    (JsPath \ "name").read[String] and
    (JsPath \ "normalisedName").read[String] and
    (JsPath \ "collaborators").read[Set[Collaborator]] and
    (JsPath \ "description").readNullable[String] and
    (JsPath \ "wso2ApplicationName").read[String] and
    (JsPath \ "tokens").read[ApplicationTokens] and
    (JsPath \ "state").read[ApplicationState] and
    (JsPath \ "access").read[Access] and
    (JsPath \ "createdOn").read[DateTime] and
    (JsPath \ "lastAccess").readNullable[DateTime] and
    (JsPath \ "deleteNotificationSent").readNullable[DateTime] and
    (JsPath \ "rateLimitTier").readNullable[RateLimitTier] and
    (JsPath \ "environment").read[String] and
    (JsPath \ "checkInformation").readNullable[CheckInformation] and
    ((JsPath \ "blocked").read[Boolean] or Reads.pure(false)) and
    ((JsPath \ "ipWhitelist").read[Set[String]] or Reads.pure(Set.empty[String]))
  )(ApplicationData.apply _)

  implicit val formatApplicationData = {
    OFormat(applicationDataReads, Json.writes[ApplicationData])
  }

  implicit val formatPaginationTotla = Json.format[PaginationTotal]
  implicit val formatPaginatedApplicationData = Json.format[PaginatedApplicationData]

  implicit val formatApplicationId= Json.format[ApplicationId]
  implicit val formatApplicationWithSubscriptionCount = Json.format[ApplicationWithSubscriptionCount]

}

object EnumJson {

  def enumReads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
    def reads(json: JsValue): JsResult[E#Value] = json match {
      case JsString(s) =>
        try {
          JsSuccess(enum.withName(s))
        } catch {
          case _: NoSuchElementException =>
            throw new InvalidEnumException(enum.getClass.getSimpleName, s)
        }
      case _ => JsError("String value expected")
    }
  }

  implicit def enumWrites[E <: Enumeration]: Writes[E#Value] = new Writes[E#Value] {
    def writes(v: E#Value): JsValue = JsString(v.toString)
  }

  implicit def enumFormat[E <: Enumeration](enum: E): Format[E#Value] = {
    Format(enumReads(enum), enumWrites)
  }

}

class InvalidEnumException(className: String, input:String)
  extends RuntimeException(s"Enumeration expected of type: '$className', but it does not contain '$input'")

object APIStatusJson {

  def apiStatusReads[APIStatus](apiStatus: APIStatus): Reads[ApiStatus.Value] = new Reads[ApiStatus.Value] {
    def reads(json: JsValue): JsResult[ApiStatus.Value] = json match {
      case JsString("PROTOTYPED") => JsSuccess(ApiStatus.BETA)
      case JsString("PUBLISHED") => JsSuccess(ApiStatus.STABLE)
      case JsString(s) =>
        try {
          JsSuccess(ApiStatus.withName(s))
        } catch {
          case _: NoSuchElementException =>
            JsError(s"Enumeration expected of type: ApiStatus, but it does not contain '$s'")
        }
      case _ => JsError("String value expected")
    }
  }

  implicit def apiStatusWrites: Writes[ApiStatus.Value] = new Writes[ApiStatus.Value] {
    def writes(v: ApiStatus.Value): JsValue = JsString(v.toString)
  }

  implicit def apiStatusFormat[APIStatus](apiStatus: APIStatus): Format[ApiStatus.Value] = {
    Format(apiStatusReads(apiStatus), apiStatusWrites)
  }

}
