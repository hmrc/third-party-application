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

import java.security.MessageDigest
import java.util.UUID

import com.google.common.base.Charsets
import javax.inject.Inject
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.{PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.thirdpartyapplication.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.models.Role.Role
import uk.gov.hmrc.thirdpartyapplication.models.State.{PRODUCTION, State, TESTING}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.time.DateTimeUtils

trait ApplicationRequest {
  val name: String
  val description: Option[String] = None
  val access: Access
}

case class CreateApplicationRequest(override val name: String,
                                    override val access: Access = Standard(Seq.empty, None, None, Set.empty),
                                    override val description: Option[String] = None,
                                    environment: Environment,
                                    collaborators: Set[Collaborator]) extends ApplicationRequest {

  def normaliseCollaborators: CreateApplicationRequest = {
    val normalised = collaborators.map(c => c.copy(emailAddress = c.emailAddress.toLowerCase))
    copy(collaborators = normalised)
  }

  require(name.nonEmpty, s"name is required")
  require(collaborators.exists(_.role == Role.ADMINISTRATOR), s"at least one ADMINISTRATOR collaborator is required")
  require(collaborators.size == collaborators.map(_.emailAddress.toLowerCase).size, "duplicate email in collaborator")
  access match {
    case a: Standard => require(a.redirectUris.size <= 5, "maximum number of redirect URIs exceeded")
    case _ =>
  }
}

case class UpdateApplicationRequest(override val name: String,
                                    override val access: Access = Standard(),
                                    override val description: Option[String] = None) extends ApplicationRequest {
  require(name.nonEmpty, s"name is required")
  access match {
    case a: Standard => require(a.redirectUris.size <= 5, "maximum number of redirect URIs exceeded")
    case _ =>
  }
}

case class ContactDetails(fullname: String, email: String, telephoneNumber: String)

object ContactDetails {
  implicit val formatContactDetails = Json.format[ContactDetails]
}

case class TermsOfUseAgreement(emailAddress: String, timeStamp: DateTime, version: String)

case class CheckInformation(contactDetails: Option[ContactDetails] = None,
                            confirmedName: Boolean = false,
                            apiSubscriptionsConfirmed: Boolean = false,
                            providedPrivacyPolicyURL: Boolean = false,
                            providedTermsAndConditionsURL: Boolean = false,
                            applicationDetails: Option[String] = None,
                            termsOfUseAgreements: Seq[TermsOfUseAgreement] = Seq.empty)

case class ApplicationResponse(id: UUID,
                               clientId: String,
                               gatewayId: String,
                               name: String,
                               deployedTo: String,
                               description: Option[String] = None,
                               collaborators: Set[Collaborator],
                               createdOn: DateTime,
                               lastAccess: Option[DateTime],
                               redirectUris: Seq[String] = Seq.empty,
                               termsAndConditionsUrl: Option[String] = None,
                               privacyPolicyUrl: Option[String] = None,
                               access: Access = Standard(Seq.empty, None, None),
                               environment: Option[Environment] = None,
                               state: ApplicationState = ApplicationState(name = TESTING),
                               rateLimitTier: RateLimitTier = BRONZE,
                               trusted: Boolean = false,
                               checkInformation: Option[CheckInformation] = None,
                               blocked: Boolean = false)

object ApplicationResponse {

  def apply(data: ApplicationData, trusted: Boolean) : ApplicationResponse = {
    val redirectUris = data.access match {
      case a: Standard => a.redirectUris
      case _ => Seq()
    }
    val termsAndConditionsUrl = data.access match {
      case a: Standard => a.termsAndConditionsUrl
      case _ => None
    }
    val privacyPolicyUrl = data.access match {
      case a: Standard => a.privacyPolicyUrl
      case _ => None
    }

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
      redirectUris,
      termsAndConditionsUrl,
      privacyPolicyUrl,
      data.access,
      Some(Environment.PRODUCTION),
      data.state,
      data.rateLimitTier.getOrElse(BRONZE),
      trusted,
      data.checkInformation,
      data.blocked)
  }
}

case class PaginatedApplicationResponse(applications: Seq[ApplicationResponse], page: Int, pageSize: Int, total: Int, matching: Int)

case class PaginationTotal(total: Int)

case class PaginatedApplicationData(applications: Seq[ApplicationData], totals: Seq[PaginationTotal], matching: Seq[PaginationTotal])

case class CreateApplicationResponse(application: ApplicationResponse, totp: Option[TotpSecrets] = None)

sealed trait Access {
  val accessType: AccessType.Value
}

case class Standard(redirectUris: Seq[String] = Seq.empty,
                    termsAndConditionsUrl: Option[String] = None,
                    privacyPolicyUrl: Option[String] = None,
                    overrides: Set[OverrideFlag] = Set.empty) extends Access {
  override val accessType = STANDARD
}

case class Privileged(totpIds: Option[TotpIds] = None, scopes: Set[String] = Set.empty) extends Access {
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

object OverrideType extends Enumeration {
  val PERSIST_LOGIN_AFTER_GRANT, GRANT_WITHOUT_TAXPAYER_CONSENT, SUPPRESS_IV_FOR_AGENTS, SUPPRESS_IV_FOR_ORGANISATIONS, SUPPRESS_IV_FOR_INDIVIDUALS = Value
}

case class ApplicationWithUpliftRequest(id: UUID,
                                        name: String,
                                        submittedOn: DateTime,
                                        state: State)

case class ApplicationWithHistory(application: ApplicationResponse, history: Seq[StateHistoryResponse])

case class APIIdentifier(context: String, version: String)

case class Wso2Api(name: String, version: String)

case class Collaborator(emailAddress: String, role: Role)

case class ClientSecret(name: String,
                        secret: String = UUID.randomUUID().toString,
                        createdOn: DateTime = DateTimeUtils.now)

case class EnvironmentToken(clientId: String,
                            wso2ClientSecret: String,
                            accessToken: String,
                            clientSecrets: Seq[ClientSecret] = Seq(ClientSecret("Default")))

case class ApplicationTokensResponse(production: EnvironmentTokenResponse,
                                     sandbox: EnvironmentTokenResponse)

case class EnvironmentTokenResponse(clientId: String,
                                    accessToken: String,
                                    clientSecrets: Seq[ClientSecret])

case class Wso2Credentials(clientId: String,
                           accessToken: String,
                           wso2Secret: String)

object Role extends Enumeration {
  type Role = Value
  val DEVELOPER, ADMINISTRATOR = Value
}

object Environment extends Enumeration {
  type Environment = Value
  val PRODUCTION, SANDBOX = Value
}

object State extends Enumeration {
  type State = Value
  val TESTING, PENDING_GATEKEEPER_APPROVAL, PENDING_REQUESTER_VERIFICATION, PRODUCTION = Value
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

    def verificationCode(input: String = UUID.randomUUID().toString): String = {
      def urlSafe(encoded: String) = encoded.replace("=", "").replace("/", "_").replace("+", "-")

      val digest = MessageDigest.getInstance("SHA-256")
      urlSafe(new String(Base64.encodeBase64(digest.digest(input.getBytes(Charsets.UTF_8))), Charsets.UTF_8))
    }

    copy(name = State.PENDING_REQUESTER_VERIFICATION, verificationCode = Some(verificationCode()), updatedOn = DateTimeUtils.now)
  }

}

class ApplicationResponseCreator @Inject()(trustedApplications: TrustedApplications) {

  def createApplicationResponse(applicationData: ApplicationData, totpSecrets: Option[TotpSecrets]) = {
    CreateApplicationResponse(ApplicationResponse(applicationData, trustedApplications.isTrusted(applicationData)), totpSecrets)
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

object ApplicationTokensResponse {

  def apply(environmentTokenResponse: EnvironmentTokenResponse): ApplicationTokensResponse = {
    ApplicationTokensResponse(
      production = environmentTokenResponse,
      sandbox = EnvironmentTokenResponse.empty
    )
  }
}

object EnvironmentTokenResponse {

  def apply(environmentToken: EnvironmentToken): EnvironmentTokenResponse = {
    EnvironmentTokenResponse(environmentToken.clientId, environmentToken.accessToken, environmentToken.clientSecrets)
  }

  def empty = {
    EnvironmentTokenResponse("", "", Seq())
  }
}



object Wso2Api {

  def create(api: APIIdentifier) = {
    Wso2Api(name(api), api.version)
  }

  private def name(api: APIIdentifier) = {
    s"${api.context.replaceAll("/", "--")}--${api.version}"
  }

}

object APIIdentifier {

  def create(wso2API: Wso2Api) = {
    APIIdentifier(context(wso2API), wso2API.version)
  }

  private def context(wso2API: Wso2Api) = {
    wso2API.name.dropRight(s"--${wso2API.version}".length).replaceAll("--", "/")
  }

}

object RateLimitTier extends Enumeration {
  type RateLimitTier = Value

  val PLATINUM, GOLD, SILVER, BRONZE = Value
}

sealed trait ApplicationStateChange

case object UpliftRequested extends ApplicationStateChange

case object UpliftApproved extends ApplicationStateChange

case object UpliftRejected extends ApplicationStateChange

case object UpliftVerified extends ApplicationStateChange

case object VerificationResent extends ApplicationStateChange

case object Deleted extends ApplicationStateChange

case object Blocked extends ApplicationStateChange

case object Unblocked extends ApplicationStateChange
