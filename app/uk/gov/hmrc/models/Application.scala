/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.models

import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

import com.google.common.base.Charsets
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.config.AppContext
import uk.gov.hmrc.models.AccessType.{PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.models.Environment.Environment
import uk.gov.hmrc.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.models.Role.Role
import uk.gov.hmrc.models.State.{PRODUCTION, State, TESTING}
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
                               name: String,
                               deployedTo: String,
                               description: Option[String] = None,
                               collaborators: Set[Collaborator],
                               createdOn: DateTime,
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
  private def getEnvironment(data: ApplicationData, clientId: Option[String]): Option[Environment] = {
    clientId match {
      case Some(data.tokens.production.clientId) => Some(Environment.PRODUCTION)
      case Some(data.tokens.sandbox.clientId) => Some(Environment.SANDBOX)
      case _ => None
    }
  }

  def apply(data: ApplicationData, clientId: Option[String], trusted: Boolean): ApplicationResponse = {
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
      data.name,
      data.environment,
      data.description,
      data.collaborators,
      data.createdOn,
      redirectUris,
      termsAndConditionsUrl,
      privacyPolicyUrl,
      data.access,
      getEnvironment(data, clientId),
      data.state,
      data.rateLimitTier.getOrElse(BRONZE),
      trusted,
      data.checkInformation,
      data.blocked)
  }
}

case class ApplicationData(id: UUID,
                           name: String,
                           normalisedName: String,
                           collaborators: Set[Collaborator],
                           description: Option[String] = None,
                           wso2Username: String,
                           wso2Password: String,
                           wso2ApplicationName: String,
                           tokens: ApplicationTokens,
                           state: ApplicationState,
                           access: Access = Standard(Seq.empty, None, None),
                           createdOn: DateTime = DateTimeUtils.now,
                           rateLimitTier: Option[RateLimitTier] = Some(BRONZE),
                           environment: String = Environment.PRODUCTION.toString,
                           checkInformation: Option[CheckInformation] = None,
                           blocked: Boolean = false) {
  lazy val admins = collaborators.filter(_.role == Role.ADMINISTRATOR)
}

case class CreateApplicationResponse(application: ApplicationResponse, totp: Option[TotpSecrets] = None)

object AccessType extends Enumeration {
  type AccessType = Value
  val STANDARD, PRIVILEGED, ROPC = Value
}

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

object OverrideType extends Enumeration {
  val PERSIST_LOGIN_AFTER_GRANT, GRANT_WITHOUT_TAXPAYER_CONSENT, SUPPRESS_IV_FOR_AGENTS, SUPPRESS_IV_FOR_ORGANISATIONS = Value
}


case class ApplicationWithUpliftRequest(id: UUID,
                                        name: String,
                                        submittedOn: DateTime,
                                        state: State)

case class ApplicationWithHistory(application: ApplicationResponse, history: Seq[StateHistoryResponse])

case class APIIdentifier(context: String, version: String)

case class WSO2API(name: String, version: String)

case class Collaborator(emailAddress: String, role: Role)

case class ApplicationTokens(production: EnvironmentToken,
                             sandbox: EnvironmentToken) {

  def environmentToken(environment: Environment) = {
    environment match {
      case Environment.PRODUCTION => production
      case _ => sandbox
    }
  }
}

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

  def from(env: String) = Environment.values.find(e => e.toString == env.toUpperCase)
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

class ApplicationResponseCreator @Inject()(appContext: AppContext) {

  def createApplicationResponse(applicationData: ApplicationData, totpSecrets: Option[TotpSecrets]) = {
    CreateApplicationResponse(ApplicationResponse(applicationData, None, appContext.isTrusted(applicationData)), totpSecrets)
  }

  private def getEnvironment(data: ApplicationData, clientId: Option[String]): Option[Environment] = {
    clientId match {
      case Some(data.tokens.production.clientId) => Some(Environment.PRODUCTION)
      case Some(data.tokens.sandbox.clientId) => Some(Environment.SANDBOX)
      case _ => None
    }
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
  def create(applicationTokens: ApplicationTokens): ApplicationTokensResponse = {
    ApplicationTokensResponse(
      EnvironmentTokenResponse.create(applicationTokens.production),
      EnvironmentTokenResponse.create(applicationTokens.sandbox)
    )
  }
}

object EnvironmentTokenResponse {
  def create(environmentToken: EnvironmentToken): EnvironmentTokenResponse = {
    EnvironmentTokenResponse(environmentToken.clientId, environmentToken.accessToken, environmentToken.clientSecrets)
  }
}

object ApplicationData {

  def create(application: CreateApplicationRequest,
             wso2Username: String,
             wso2Password: String,
             wso2ApplicationName: String,
             tokens: ApplicationTokens): ApplicationData = {

    val applicationState = (application.environment, application.access.accessType) match {
      case (Environment.SANDBOX, _) => ApplicationState(PRODUCTION)
      case (_, PRIVILEGED | ROPC) => ApplicationState(PRODUCTION, application.collaborators.headOption.map(_.emailAddress))
      case _ => ApplicationState(TESTING)
    }

    ApplicationData(
      UUID.randomUUID,
      application.name,
      application.name.toLowerCase,
      application.collaborators,
      application.description,
      wso2Username,
      wso2Password,
      wso2ApplicationName,
      tokens,
      applicationState,
      application.access,
      environment = application.environment.toString)
  }
}

object WSO2API {

  def create(api: APIIdentifier) = {
    WSO2API(name(api), api.version)
  }

  private def name(api: APIIdentifier) = {
    s"${api.context.replaceAll("/", "--")}--${api.version}"
  }

}

object APIIdentifier {

  def create(wso2API: WSO2API) = {
    APIIdentifier(context(wso2API), wso2API.version)
  }

  private def context(wso2API: WSO2API) = {
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