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

import java.time.{Clock, LocalDateTime}

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

case class Application(
    id: ApplicationId,
    clientId: ClientId,
    gatewayId: String,
    name: String,
    deployedTo: String,
    description: Option[String] = None,
    collaborators: Set[Collaborator],
    createdOn: LocalDateTime,
    lastAccess: Option[LocalDateTime],
    grantLength: Int,
    lastAccessTokenUsage: Option[LocalDateTime] = None, // API-4376: Temporary inclusion whilst Server Token functionality is retired
    redirectUris: List[RedirectUri] = List.empty,
    termsAndConditionsUrl: Option[String] = None,
    privacyPolicyUrl: Option[String] = None,
    access: Access = Access.Standard(),
    state: ApplicationState = ApplicationState(name = State.TESTING, updatedOn = LocalDateTime.now(Clock.systemUTC)),
    rateLimitTier: RateLimitTier = RateLimitTier.BRONZE,
    checkInformation: Option[CheckInformation] = None,
    blocked: Boolean = false,
    trusted: Boolean = false,
    ipAllowlist: IpAllowlist = IpAllowlist(),
    moreApplication: MoreApplication = MoreApplication(true)
  )

object Application {

  def allowAutoDelete(data: StoredApplication): MoreApplication = Option(data.allowAutoDelete) match {
    case Some(allowAutoDeleteFlag: Boolean) => MoreApplication(allowAutoDeleteFlag)
    case _                                  => MoreApplication(false)
  }

  def redirectUris(data: StoredApplication): List[RedirectUri] = data.access match {
    case a: Access.Standard => a.redirectUris
    case _                  => List.empty
  }

  def termsAndConditionsUrl(data: StoredApplication): Option[String] = data.access match {
    case a: Access.Standard => a.termsAndConditionsUrl
    case _                  => None
  }

  def privacyPolicyUrl(data: StoredApplication): Option[String] = data.access match {
    case a: Access.Standard => a.privacyPolicyUrl
    case _                  => None
  }

  def apply(data: StoredApplication): Application = {
    Application(
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
      data.rateLimitTier.getOrElse(RateLimitTier.BRONZE),
      data.checkInformation,
      data.blocked,
      ipAllowlist = data.ipAllowlist,
      moreApplication = allowAutoDelete(data)
    )
  }
}
