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

import java.security.MessageDigest

import com.google.common.base.Charsets
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.{PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.models.OverrideType
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.{PRODUCTION, State, TESTING}
import uk.gov.hmrc.thirdpartyapplication.domain.models.StateHistory
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.time.DateTimeUtils
import java.{util => ju}
import uk.gov.hmrc.thirdpartyapplication.domain.models.CheckInformation
import uk.gov.hmrc.thirdpartyapplication.domain.models.Collaborator
import uk.gov.hmrc.thirdpartyapplication.domain.models._

trait ApplicationRequest {
  val name: String
  val description: Option[String] = None
  val access: Access
}

case class CreateApplicationRequest(override val name: String,
                                    override val access: Access = Standard(List.empty, None, None, Set.empty),
                                    override val description: Option[String] = None,
                                    environment: Environment,
                                    collaborators: Set[Collaborator],
                                    subscriptions: List[ApiIdentifier] = List.empty) extends ApplicationRequest {

  def normaliseCollaborators: CreateApplicationRequest = {
    val normalised = collaborators.map(c => c.copy(emailAddress = c.emailAddress.toLowerCase))
    copy(collaborators = normalised)
  }

  require(name.nonEmpty, "name is required")
  require(collaborators.exists(_.role == Role.ADMINISTRATOR), "at least one ADMINISTRATOR collaborator is required")
  require(collaborators.size == collaborators.map(_.emailAddress.toLowerCase).size, "duplicate email in collaborator")
  access match {
    case a: Standard => require(a.redirectUris.size <= 5, "maximum number of redirect URIs exceeded")
    case _ =>
  }
}

case class UpdateApplicationRequest(override val name: String,
                                    override val access: Access = Standard(),
                                    override val description: Option[String] = None) extends ApplicationRequest {
  require(name.nonEmpty, "name is required")
  access match {
    case a: Standard => require(a.redirectUris.size <= 5, "maximum number of redirect URIs exceeded")
    case _ =>
  }
}




case class ApplicationResponse(id: ApplicationId,
                               clientId: ClientId,
                               gatewayId: String,
                               name: String,
                               deployedTo: String,
                               description: Option[String] = None,
                               collaborators: Set[Collaborator],
                               createdOn: DateTime,
                               lastAccess: Option[DateTime],
                               lastAccessTokenUsage: Option[DateTime] = None, // API-4376: Temporary inclusion whilst Server Token functionality is retired
                               redirectUris: List[String] = List.empty,
                               termsAndConditionsUrl: Option[String] = None,
                               privacyPolicyUrl: Option[String] = None,
                               access: Access = Standard(List.empty, None, None),
                               state: ApplicationState = ApplicationState(name = TESTING),
                               rateLimitTier: RateLimitTier = BRONZE,
                               checkInformation: Option[CheckInformation] = None,
                               blocked: Boolean = false,
                               trusted: Boolean = false,
                               ipAllowlist: IpAllowlist = IpAllowlist())

object ApplicationResponse {

  def redirectUris(data: ApplicationData): List[String] = data.access match {
    case a: Standard => a.redirectUris
    case _ => List.empty
  }
  def termsAndConditionsUrl(data: ApplicationData): Option[String] = data.access match {
    case a: Standard => a.termsAndConditionsUrl
    case _ => None
  }
  def privacyPolicyUrl(data: ApplicationData): Option[String] = data.access match {
    case a: Standard => a.privacyPolicyUrl
    case _ => None
  }

  def apply(data: ApplicationData): ApplicationResponse = {
    ApplicationResponse(
      data.id,
      data.tokens.production.clientId,
      data.wso2ApplicationName,
      data.name,
      data.environment,
      data.description,
      data.collaborators,
      data.createdOn,
      data.lastAccess,
      data.tokens.production.lastAccessTokenUsage,
      redirectUris(data),
      termsAndConditionsUrl(data),
      privacyPolicyUrl(data),
      data.access,
      data.state,
      data.rateLimitTier.getOrElse(BRONZE),
      data.checkInformation,
      data.blocked,
      ipAllowlist= data.ipAllowlist)
  }
}

case class ExtendedApplicationResponse(id: ApplicationId,
                                       clientId: ClientId,
                                       gatewayId: String,
                                       name: String,
                                       deployedTo: String,
                                       description: Option[String] = None,
                                       collaborators: Set[Collaborator],
                                       createdOn: DateTime,
                                       lastAccess: Option[DateTime],
                                       redirectUris: List[String] = List.empty,
                                       termsAndConditionsUrl: Option[String] = None,
                                       privacyPolicyUrl: Option[String] = None,
                                       access: Access = Standard(List.empty, None, None),
                                       state: ApplicationState = ApplicationState(name = TESTING),
                                       rateLimitTier: RateLimitTier = BRONZE,
                                       checkInformation: Option[CheckInformation] = None,
                                       blocked: Boolean = false,
                                       trusted: Boolean = false,
                                       serverToken: String,
                                       subscriptions: List[ApiIdentifier],
                                       ipAllowlist: IpAllowlist = IpAllowlist())

object ExtendedApplicationResponse {
  def apply(data: ApplicationData, subscriptions: List[ApiIdentifier]): ExtendedApplicationResponse = {
    ExtendedApplicationResponse(
      data.id,
      data.tokens.production.clientId,
      data.wso2ApplicationName,
      data.name,
      data.environment,
      data.description,
      data.collaborators,
      data.createdOn,
      data.lastAccess,
      ApplicationResponse.redirectUris(data),
      ApplicationResponse.termsAndConditionsUrl(data),
      ApplicationResponse.privacyPolicyUrl(data),
      data.access,
      data.state,
      data.rateLimitTier.getOrElse(BRONZE),
      data.checkInformation,
      data.blocked,
      serverToken = data.tokens.production.accessToken,
      subscriptions = subscriptions,
      ipAllowlist = data.ipAllowlist)
  }
}

case class PaginatedApplicationResponse(applications: List[ApplicationResponse], page: Int, pageSize: Int, total: Int, matching: Int)

case class PaginationTotal(total: Int)

case class PaginatedApplicationData(applications: List[ApplicationData], totals: List[PaginationTotal], matching: List[PaginationTotal])

case class CreateApplicationResponse(application: ApplicationResponse, totp: Option[TotpSecret] = None)

case class ApplicationLabel(id: String, name: String)
case class ApplicationWithSubscriptionCount(_id: ApplicationLabel, count: Int)

sealed trait Access {
  val accessType: AccessType.Value
}

case class Standard(redirectUris: List[String] = List.empty,
                    termsAndConditionsUrl: Option[String] = None,
                    privacyPolicyUrl: Option[String] = None,
                    overrides: Set[OverrideFlag] = Set.empty) extends Access {
  override val accessType = STANDARD
}

case class Privileged(totpIds: Option[TotpId] = None, scopes: Set[String] = Set.empty) extends Access {
  override val accessType = PRIVILEGED
}

case class Ropc(scopes: Set[String] = Set.empty) extends Access {
  override val accessType = ROPC
}

sealed trait OverrideFlag {
  val overrideType: OverrideType.Value
}

case class PersistLogin() extends OverrideFlag {
  val overrideType = OverrideType.PERSIST_LOGIN_AFTER_GRANT
}

case class SuppressIvForAgents(scopes: Set[String]) extends OverrideFlag {
  val overrideType = OverrideType.SUPPRESS_IV_FOR_AGENTS
}

case class SuppressIvForOrganisations(scopes: Set[String]) extends OverrideFlag {
  val overrideType = OverrideType.SUPPRESS_IV_FOR_ORGANISATIONS
}

case class GrantWithoutConsent(scopes: Set[String]) extends OverrideFlag {
  val overrideType = OverrideType.GRANT_WITHOUT_TAXPAYER_CONSENT
}

case class SuppressIvForIndividuals(scopes: Set[String]) extends OverrideFlag {
  val overrideType = OverrideType.SUPPRESS_IV_FOR_INDIVIDUALS
}


case class ApplicationWithUpliftRequest(id: ApplicationId,
                                        name: String,
                                        submittedOn: DateTime,
                                        state: State)

case class ApplicationWithHistory(application: ApplicationResponse, history: List[StateHistoryResponse])



trait Token {
  def clientId: ClientId
  def accessToken: String
  def clientSecrets: List[ClientSecret]
}

case class EnvironmentToken(clientId: ClientId,
                            accessToken: String,
                            clientSecrets: List[ClientSecret] = List(),
                            lastAccessTokenUsage: Option[DateTime] = None) extends Token

case class ApplicationTokenResponse(
   clientId: ClientId,
   accessToken: String,
   clientSecrets: List[ClientSecretResponse]
)

object ApplicationTokenResponse {
  def apply(token: Token): ApplicationTokenResponse =
    new ApplicationTokenResponse(
      clientId = token.clientId,
      accessToken = token.accessToken,
      clientSecrets = token.clientSecrets map { ClientSecretResponse(_) }
    )

  def apply(token: Token, newClientSecretId: String, newClientSecret: String): ApplicationTokenResponse =
    new ApplicationTokenResponse(
      clientId = token.clientId,
      accessToken = token.accessToken,
      clientSecrets = token.clientSecrets map { ClientSecretResponse(_, newClientSecretId, newClientSecret) }
    )
}

case class ClientSecretResponse(id: String,
                                name: String,
                                secret: Option[String],
                                createdOn: DateTime,
                                lastAccess: Option[DateTime])

object ClientSecretResponse {
  def apply(clientSecret: ClientSecret): ClientSecretResponse =
    ClientSecretResponse(clientSecret.id, clientSecret.name, None, clientSecret.createdOn, clientSecret.lastAccess)

  def apply(clientSecret: ClientSecret, newClientSecretId: String, newClientSecret: String): ClientSecretResponse =
    ClientSecretResponse(
      clientSecret.id,
      clientSecret.name,
      if (clientSecret.id == newClientSecretId) Some(newClientSecret) else None,
      clientSecret.createdOn,
      clientSecret.lastAccess)
}


case class ApplicationState(name: State = TESTING, requestedByEmailAddress: Option[String] = None,
                            verificationCode: Option[String] = None, updatedOn: DateTime = DateTimeUtils.now) {

  final def requireState(requirement: State, transitionTo: State): Unit = {
    if (name != requirement) {
      throw new InvalidStateTransition(expectedFrom = requirement, invalidFrom = name, to = transitionTo)
    }
  }

  def toProduction = {
    requireState(requirement = State.PENDING_REQUESTER_VERIFICATION, transitionTo = PRODUCTION)
    copy(name = PRODUCTION, updatedOn = DateTimeUtils.now)
  }

  def toTesting = copy(name = TESTING, requestedByEmailAddress = None, verificationCode = None, updatedOn = DateTimeUtils.now)

  def toPendingGatekeeperApproval(requestedByEmailAddress: String) = {
    requireState(requirement = TESTING, transitionTo = State.PENDING_GATEKEEPER_APPROVAL)

    copy(name = State.PENDING_GATEKEEPER_APPROVAL,
      updatedOn = DateTimeUtils.now,
      requestedByEmailAddress = Some(requestedByEmailAddress))
  }

  def toPendingRequesterVerification = {
    requireState(requirement = State.PENDING_GATEKEEPER_APPROVAL, transitionTo = State.PENDING_REQUESTER_VERIFICATION)

    def verificationCode(input: String = ju.UUID.randomUUID().toString): String = {
      def urlSafe(encoded: String) = encoded.replace("=", "").replace("/", "_").replace("+", "-")

      val digest = MessageDigest.getInstance("SHA-256")
      urlSafe(new String(Base64.encodeBase64(digest.digest(input.getBytes(Charsets.UTF_8))), Charsets.UTF_8))
    }
    copy(name = State.PENDING_REQUESTER_VERIFICATION, verificationCode = Some(verificationCode()), updatedOn = DateTimeUtils.now)
  }

}

class ApplicationResponseCreator {

  def createApplicationResponse(applicationData: ApplicationData, totpSecrets: Option[TotpSecret]) = {
    CreateApplicationResponse(ApplicationResponse(applicationData), totpSecrets)
  }
}

object ApplicationWithUpliftRequest {
  def create(app: ApplicationData, upliftRequest: StateHistory): ApplicationWithUpliftRequest = {
    if (upliftRequest.state != State.PENDING_GATEKEEPER_APPROVAL) {
      throw new InconsistentDataState(s"cannot create with invalid state: ${upliftRequest.state}")
    }
    ApplicationWithUpliftRequest(app.id, app.name, upliftRequest.changedAt, app.state.name)
  }

}


sealed trait ApplicationStateChange

case object UpliftRequested extends ApplicationStateChange

case object UpliftApproved extends ApplicationStateChange

case object UpliftRejected extends ApplicationStateChange

case object UpliftVerified extends ApplicationStateChange

case object VerificationResent extends ApplicationStateChange

case object Deleted extends ApplicationStateChange

trait Blocked extends ApplicationStateChange

case object Blocked extends Blocked

trait Unblocked extends ApplicationStateChange

case object Unblocked extends Unblocked
