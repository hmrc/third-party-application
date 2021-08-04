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

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.play.json.Union
import uk.gov.hmrc.thirdpartyapplication.controllers.{ApplicationNameValidationRequest, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.{PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.thirdpartyapplication.domain.models.OverrideType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.utils.DateTimeFormatters

trait JsonFormatters extends DateTimeFormatters {

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

  private implicit val formatStandard = Json.format[Standard]
  private implicit val formatPrivileged = Json.format[Privileged]
  private implicit val formatRopc = Json.format[Ropc]

  implicit val formatAccess = Union.from[Access]("accessType")
    .and[Standard](STANDARD.toString)
    .and[Privileged](PRIVILEGED.toString)
    .and[Ropc](ROPC.toString)
    .format

  implicit val formatTermsOfUserAgreement = Json.format[TermsOfUseAgreement]
  implicit val formatCheckInformation = Json.format[CheckInformation]

  implicit val formatApplicationState = Json.format[ApplicationState]
  implicit val formatClientSecret = Json.format[ClientSecret]
  implicit val formatEnvironmentToken = Json.format[Token]
  implicit val formatApplicationTokens = Json.format[ApplicationTokens]

  implicit val formatApplicationData = Json.format[ApplicationData]

  val createApplicationRequestReads: Reads[CreateApplicationRequest] = (
    (JsPath \ "name").read[String] and
    (JsPath \ "access").read[Access] and
    (JsPath \ "description").readNullable[String] and
    (JsPath \ "environment").read[Environment] and
    (JsPath \ "collaborators").read[Set[Collaborator]] and
    ((JsPath \ "subscriptions").read[List[ApiIdentifier]] or Reads.pure(List.empty[ApiIdentifier]))
  )(CreateApplicationRequest.apply _)
  implicit val formatCreateApplicationRequest = Format(createApplicationRequestReads, Json.writes[CreateApplicationRequest])
  
  implicit val formatUpdateApplicationRequest = Json.format[UpdateApplicationRequest]
  implicit val formatApplicationResponse = Json.format[ApplicationResponse]
  implicit val formatExtendedApplicationResponse = Json.format[ExtendedApplicationResponse]
  implicit val formatPaginatedApplicationResponse = Json.format[PaginatedApplicationResponse]
  implicit val formatUpdateRateLimitTierRequest = Json.format[UpdateRateLimitTierRequest]
  implicit val formatUpdateIpAllowlistRequest = Json.format[UpdateIpAllowlistRequest]
  implicit val formatApplicationWithHistory = Json.format[ApplicationWithHistory]
  implicit val formatClientSecretResponse = Json.format[ClientSecretResponse]
  implicit val formatApplicationTokensResponse = Json.format[ApplicationTokenResponse]


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
  implicit val formatDeleteClientSecretsRequest = Json.format[DeleteClientSecretsRequest]
  implicit val formatDeleteClientSecretRequest = Json.format[DeleteClientSecretRequest]
  implicit val formatFixCollaboratorRequest = Json.format[FixCollaboratorRequest]
  implicit val formatDeleteCollaboratorRequest = Json.format[DeleteCollaboratorRequest]

  implicit val createApplicationResponseWrites: Writes[CreateApplicationResponse] = (
    JsPath.write[ApplicationResponse] and (JsPath \ "totp").write[Option[TotpSecret]]
    )(unlift(CreateApplicationResponse.unapply))
}


object MongoFormat {
implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  // Here to override default date time formatting in companion object
  implicit val formatTermsOfUseAgreement = Json.format[TermsOfUseAgreement]

  val checkInformationReads: Reads[CheckInformation] = (
    (JsPath \ "contactDetails").readNullable[ContactDetails] and
      (JsPath \ "confirmedName").read[Boolean] and
      ((JsPath \ "apiSubscriptionsConfirmed").read[Boolean] or Reads.pure(false)) and
      ((JsPath \ "apiSubscriptionConfigurationsConfirmed").read[Boolean] or Reads.pure(false)) and
      (JsPath \ "providedPrivacyPolicyURL").read[Boolean] and
      (JsPath \ "providedTermsAndConditionsURL").read[Boolean] and
      (JsPath \ "applicationDetails").readNullable[String] and
      ((JsPath \ "teamConfirmed").read[Boolean] or Reads.pure(false)) and
      ((JsPath \ "termsOfUseAgreements").read[List[TermsOfUseAgreement]] or Reads.pure(List.empty[TermsOfUseAgreement]))
    )(CheckInformation.apply _)

  implicit val checkInformationFormat = {
    Format(checkInformationReads, Json.writes[CheckInformation])
  }

  implicit val formatAccess = JsonFormatters.formatAccess
  implicit val formatApplicationState = Json.format[ApplicationState]

  implicit val formatEnvironmentToken = Json.format[Token]
  implicit val formatApplicationTokens = Json.format[ApplicationTokens]

  // Non-standard format compared to companion object
  val ipAllowlistReads: Reads[IpAllowlist] = (
    ((JsPath \ "required").read[Boolean] or Reads.pure(false)) and
    ((JsPath \ "allowlist").read[Set[String]]or Reads.pure(Set.empty[String]))
  )(IpAllowlist.apply _)
  implicit val formatIpAllowlist = OFormat(ipAllowlistReads, Json.writes[IpAllowlist])

  val applicationDataReads: Reads[ApplicationData] = (
    (JsPath \ "id").read[ApplicationId] and
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
    (JsPath \ "rateLimitTier").readNullable[RateLimitTier] and
    (JsPath \ "environment").read[String] and
    (JsPath \ "checkInformation").readNullable[CheckInformation] and
    ((JsPath \ "blocked").read[Boolean] or Reads.pure(false)) and
    ((JsPath \ "ipAllowlist").read[IpAllowlist] or Reads.pure(IpAllowlist()))
  )(ApplicationData.apply _)

  implicit val formatApplicationData = OFormat(applicationDataReads, Json.writes[ApplicationData])

  implicit val formatPaginationTotla = Json.format[PaginationTotal]
  implicit val formatPaginatedApplicationData = Json.format[PaginatedApplicationData]

  implicit val formatApplicationLabel = Json.format[ApplicationLabel]
  implicit val formatApplicationWithSubscriptionCount = Json.format[ApplicationWithSubscriptionCount]
}

object ApplicationEventFormats {
  import DateTimeFormatters._
  implicit val eventIdFormat: Format[EventId] = Json.valueFormat[EventId]
  implicit val teamMemberAddedEventFormats: OFormat[TeamMemberAddedEvent] = Json.format[TeamMemberAddedEvent]
  implicit val teamMemberRemovedEventFormats: OFormat[TeamMemberRemovedEvent] = Json.format[TeamMemberRemovedEvent]
  implicit val clientSecretAddedEventFormats: OFormat[ClientSecretAddedEvent] = Json.format[ClientSecretAddedEvent]
  implicit val clientSecretRemovedEventFormats: OFormat[ClientSecretRemovedEvent] = Json.format[ClientSecretRemovedEvent]
  implicit val urisUpdatedEventFormats: OFormat[RedirectUrisUpdatedEvent] = Json.format[RedirectUrisUpdatedEvent]
  implicit val apiSubscribedEventFormats: OFormat[ApiSubscribedEvent] =Json.format[ApiSubscribedEvent]
  implicit val apiUnsubscribedEventFormats: OFormat[ApiUnsubscribedEvent] = Json.format[ApiUnsubscribedEvent]

  implicit val formatApplicationEvent: Format[ApplicationEvent] = Union.from[ApplicationEvent]("eventType")
    .and[TeamMemberAddedEvent](EventType.TEAM_MEMBER_ADDED.toString)
    .and[TeamMemberRemovedEvent](EventType.TEAM_MEMBER_REMOVED.toString)
    .and[ClientSecretAddedEvent](EventType.CLIENT_SECRET_ADDED.toString)
    .and[ClientSecretRemovedEvent](EventType.CLIENT_SECRET_REMOVED.toString)
    .and[RedirectUrisUpdatedEvent](EventType.REDIRECT_URIS_UPDATED.toString)
    .and[ApiSubscribedEvent](EventType.API_SUBSCRIBED.toString)
    .and[ApiUnsubscribedEvent](EventType.API_UNSUBSCRIBED.toString)
    .format
}

object JsonFormatters extends JsonFormatters
