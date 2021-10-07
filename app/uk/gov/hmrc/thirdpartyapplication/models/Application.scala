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
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.{State, _}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

trait ApplicationRequest {
  def name: String
  def description: Option[String]
  def access: Access
}

trait CreateApplicationRequest extends ApplicationRequest {
  def collaborators: Set[Collaborator]
  def environment: Environment
  def anySubscriptions: Set[ApiIdentifier]

  protected def lowerCaseEmails(in: Set[Collaborator]): Set[Collaborator] = {
    in.map(c => c.copy(emailAddress = c.emailAddress.toLowerCase))
  }

  def validate(in: CreateApplicationRequest): Unit = {
    require(in.name.nonEmpty, "name is required")
    require(in.collaborators.exists(_.role == Role.ADMINISTRATOR), "at least one ADMINISTRATOR collaborator is required")
    require(in.collaborators.size == collaborators.map(_.emailAddress.toLowerCase).size, "duplicate email in collaborator")
    in.access match {
      case a: Standard => require(a.redirectUris.size <= 5, "maximum number of redirect URIs exceeded")
      case _ =>
    }
  }
}

case class CreateApplicationRequestV1(
  name: String,
  access: Access = Standard(List.empty, None, None, Set.empty),
  description: Option[String] = None,
  environment: Environment,
  collaborators: Set[Collaborator],
  subscriptions: Option[Set[ApiIdentifier]]
) extends CreateApplicationRequest {
  
  validate(this)

  def normaliseCollaborators: CreateApplicationRequestV1 = copy(collaborators = lowerCaseEmails(collaborators))

  def anySubscriptions: Set[ApiIdentifier] = subscriptions.getOrElse(Set.empty)
}

object CreateApplicationRequestV1 {
  import play.api.libs.json.Json

  implicit val reads = Json.reads[CreateApplicationRequestV1]
}

case class UpliftData(
  responsibleIndividual: ResponsibleIndividual,
  sellResellOrDistribute: SellResellOrDistribute,
  subscriptions: Set[ApiIdentifier]
)

object UpliftData {
  import play.api.libs.json.{Format, Json}

  implicit val format: Format[UpliftData] = Json.format[UpliftData]
}

case class CreateApplicationRequestV2(
  name: String,
  access: Access = Standard(List.empty, None, None, Set.empty),
  description: Option[String] = None,
  environment: Environment,
  collaborators: Set[Collaborator],
  upliftData: UpliftData
) extends CreateApplicationRequest {
                                      
  validate(this)

  lazy val anySubscriptions: Set[ApiIdentifier] = upliftData.subscriptions

  def normaliseCollaborators: CreateApplicationRequestV2 = copy(collaborators = lowerCaseEmails(collaborators))
}

object CreateApplicationRequestV2 {
  import play.api.libs.json.Json

  implicit val reads = Json.reads[CreateApplicationRequestV2]
}

object CreateApplicationRequest {
  import play.api.libs.functional.syntax._
  
  implicit val reads = CreateApplicationRequestV2.reads.map(_.asInstanceOf[CreateApplicationRequest]) or CreateApplicationRequestV1.reads.map(_.asInstanceOf[CreateApplicationRequest])
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
                               grantLength: Int,
                               lastAccessTokenUsage: Option[DateTime] = None, // API-4376: Temporary inclusion whilst Server Token functionality is retired
                               redirectUris: List[String] = List.empty,
                               termsAndConditionsUrl: Option[String] = None,
                               privacyPolicyUrl: Option[String] = None,
                               access: Access = Standard(List.empty, None, None),
                               state: ApplicationState = ApplicationState(name = State.TESTING),
                               rateLimitTier: RateLimitTier = BRONZE,
                               checkInformation: Option[CheckInformation] = None,
                               blocked: Boolean = false,
                               trusted: Boolean = false,
                               ipAllowlist: IpAllowlist = IpAllowlist()/*,
                               responsibleIndividual: Option[ResponsibleIndividual] = None,
                               sellResellOrDistribute: Option[SellResellOrDistribute] = None*/
                               )

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
      data.grantLength,
      data.tokens.production.lastAccessTokenUsage,
      redirectUris(data),
      termsAndConditionsUrl(data),
      privacyPolicyUrl(data),
      data.access,
      data.state,
      data.rateLimitTier.getOrElse(BRONZE),
      data.checkInformation,
      data.blocked,
      ipAllowlist= data.ipAllowlist /*,
      responsibleIndividual = data.responsibleIndividual,
      sellResellOrDistribute = data.sellResellOrDistribute */
    )
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
                                       grantLength: Int,
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
      data.grantLength,
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


case class CreateApplicationResponse(application: ApplicationResponse, totp: Option[TotpSecret] = None)



case class ApplicationWithUpliftRequest(id: ApplicationId,
                                        name: String,
                                        submittedOn: DateTime,
                                        state: State)

case class ApplicationWithHistory(application: ApplicationResponse, history: List[StateHistoryResponse])


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

