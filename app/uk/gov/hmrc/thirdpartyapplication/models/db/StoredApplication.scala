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

package uk.gov.hmrc.thirdpartyapplication.models.db

import java.time.temporal.ChronoUnit
import java.time.{Instant, Period}

import com.typesafe.config.ConfigFactory

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, AccessType}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ImportantSubmissionData
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication.grantLengthConfig

case class StoredApplication(
    id: ApplicationId,
    name: ApplicationName,
    normalisedName: String,
    collaborators: Set[Collaborator],
    description: Option[String] = None,
    wso2ApplicationName: String,
    tokens: ApplicationTokens,
    state: ApplicationState,
    access: Access = Access.Standard(),
    createdOn: Instant,
    lastAccess: Option[Instant],
    refreshTokensAvailableFor: Period = Period.ofDays(grantLengthConfig),
    rateLimitTier: Option[RateLimitTier] = Some(RateLimitTier.BRONZE),
    environment: String = Environment.PRODUCTION.toString,
    checkInformation: Option[CheckInformation] = None,
    blocked: Boolean = false,
    ipAllowlist: IpAllowlist = IpAllowlist(),
    allowAutoDelete: Boolean = true
  ) extends HasState with HasAccess with HasCollaborators {

  lazy val sellResellOrDistribute = access match {
    case Access.Standard(_, _, _, _, sellResellOrDistribute, _) => sellResellOrDistribute
    case _                                                      => None
  }

  lazy val importantSubmissionData: Option[ImportantSubmissionData] = access match {
    case Access.Standard(_, _, _, _, _, Some(submissionData)) => Some(submissionData)
    case _                                                    => None
  }
}

object StoredApplication {

  def asApplication(data: StoredApplication): ApplicationWithCollaborators = {
    ApplicationWithCollaborators(
      CoreApplication(
        data.id,
        data.tokens.production.clientId,
        data.wso2ApplicationName,
        data.name,
        Environment.unsafeApply(data.environment),
        data.description,
        data.createdOn,
        data.lastAccess,
        GrantLength.apply(data.refreshTokensAvailableFor).getOrElse(GrantLength.EIGHTEEN_MONTHS),
        data.tokens.production.lastAccessTokenUsage,
        data.access,
        data.state,
        data.rateLimitTier.getOrElse(RateLimitTier.BRONZE),
        data.checkInformation,
        data.blocked,
        ipAllowlist = data.ipAllowlist,
        allowAutoDelete = data.allowAutoDelete,
        lastActionActor = ActorType.UNKNOWN
      ),
      data.collaborators
    )
  }

  val grantLengthConfig = ConfigFactory.load().getInt("grantLengthInDays")

  def create(
      createApplicationRequest: CreateApplicationRequest,
      wso2ApplicationName: String,
      environmentToken: StoredToken,
      createdOn: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    ): StoredApplication = {
    import createApplicationRequest._

    val applicationState = (environment, accessType) match {
      case (Environment.SANDBOX, _)                     => ApplicationState(State.PRODUCTION, updatedOn = createdOn)
      case (_, AccessType.PRIVILEGED | AccessType.ROPC) => ApplicationState(State.PRODUCTION, collaborators.headOption.map(_.emailAddress.text), updatedOn = createdOn)
      case _                                            => ApplicationState(State.TESTING, updatedOn = createdOn)
    }

    val checkInfo = createApplicationRequest match {
      case v1: CreateApplicationRequestV1 if (v1.anySubscriptions.nonEmpty) => Some(CheckInformation(apiSubscriptionsConfirmed = true))
      case v2: CreateApplicationRequestV2                                   => None
      case x: CreateApplicationRequestV1                                    => None
    }

    val applicationAccess = createApplicationRequest match {
      case v1: CreateApplicationRequestV1 => v1.access
      case v2: CreateApplicationRequestV2 =>
        Access.Standard().copy(redirectUris = v2.access.redirectUris, overrides = v2.access.overrides, sellResellOrDistribute = Some(v2.upliftRequest.sellResellOrDistribute))
    }

    StoredApplication(
      ApplicationId.random,
      name,
      name.value.toLowerCase,
      collaborators,
      createApplicationRequest.description.filterNot(_ => environment == Environment.PRODUCTION),
      wso2ApplicationName,
      ApplicationTokens(environmentToken),
      applicationState,
      applicationAccess,
      createdOn,
      Some(createdOn),
      environment = environment.toString,
      checkInformation = checkInfo
    )
  }
}
