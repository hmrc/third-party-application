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

import java.time.{Clock, Instant}

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

case class ExtendedApplicationResponse(
    id: ApplicationId,
    clientId: ClientId,
    gatewayId: String,
    name: String,
    deployedTo: String,
    description: Option[String] = None,
    collaborators: Set[Collaborator],
    createdOn: Instant,
    lastAccess: Option[Instant],
    grantLength: GrantLength,
    redirectUris: List[RedirectUri] = List.empty,
    termsAndConditionsUrl: Option[String] = None,
    privacyPolicyUrl: Option[String] = None,
    access: Access = Access.Standard(),
    state: ApplicationState = ApplicationState(name = State.TESTING, updatedOn = Instant.now(Clock.systemUTC)),
    rateLimitTier: RateLimitTier = RateLimitTier.BRONZE,
    checkInformation: Option[CheckInformation] = None,
    blocked: Boolean = false,
    serverToken: String,
    subscriptions: List[ApiIdentifier],
    ipAllowlist: IpAllowlist = IpAllowlist(),
    allowAutoDelete: Boolean
  )

object ExtendedApplicationResponse {

  def apply(data: StoredApplication, subscriptions: List[ApiIdentifier]): ExtendedApplicationResponse = {
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
      GrantLength.apply(data.refreshTokensAvailableFor).getOrElse(GrantLength.EIGHTEEN_MONTHS),
      Application.redirectUris(data),
      Application.termsAndConditionsUrl(data),
      Application.privacyPolicyUrl(data),
      data.access,
      data.state,
      data.rateLimitTier.getOrElse(RateLimitTier.BRONZE),
      data.checkInformation,
      data.blocked,
      serverToken = data.tokens.production.accessToken,
      subscriptions = subscriptions,
      ipAllowlist = data.ipAllowlist,
      allowAutoDelete = data.allowAutoDelete
    )
  }
}
