package uk.gov.hmrc.thirdpartyapplication.domain.models

import org.joda.time.DateTime
import RateLimitTier.RateLimitTier


case class ClientData(
  accessToken: String,
  clientSecrets: List[ClientSecret],                  // Non Empty
  lastAccessTokenUsage: Option[DateTime] = None,
  createdOn: DateTime,
  lastAccess: Option[DateTime],
  rateLimitTier: Option[RateLimitTier],
  ipAllowlist: IpAllowlist
)

case class Application(
  id: ApplicationId,
  name: String,
  normalisedName: String,
  collaborators: Set[Collaborator],                   // Non empty
  description: Option[String] = None,
  wso2ApplicationName: String,
  clientData: Map[ClientId, ClientData],              // Non empty
  state: ApplicationState,
  access: Access = Standard(List.empty, None, None),
  environment: String = Environment.PRODUCTION.toString,
  checkInformation: Option[CheckInformation] = None,
  createdOn: DateTime,
  blocked: Boolean = false,
  subscriptions: Set[ApiIdentifier]
)


// ------------------------------------------------------


case class InsecureClientDataResponse(
  createdOn: DateTime,
  lastAccess: Option[DateTime],
  rateLimitTier: Option[RateLimitTier],
  ipAllowlist: IpAllowlist = IpAllowlist(), 
  lastAccessTokenUsage: Option[DateTime],
)

case class FlexibleApplicationResponse(
  id: ApplicationId,
  name: String,
  normalisedName: String,
  description: Option[String],
  wso2ApplicationName: String,
  state: ApplicationState,
  access: Access,
  environment: String,
  checkInformation: Option[CheckInformation],
  createdOn: DateTime,
  blocked: Boolean,
  clientData: Option[Map[ClientId, InsecureClientDataResponse]],
  collaborators: Option[Set[Collaborator]],
  subscriptions: Option[Set[ApiIdentifier]]
)


