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

import play.api.Configuration
import uk.gov.hmrc.thirdpartyapplication.models.ApiStatus.APIStatus

case class ApiDefinition(serviceName: String,
                         name: String,
                         context: String,
                         versions: List[ApiVersion],
                         isTestSupport: Option[Boolean] = None)

case class ApiVersion(version: String,
                      status: APIStatus,
                      access: Option[ApiAccess])

case class ApiAccess(`type`: APIAccessType.Value, whitelistedApplicationIds: Option[List[String]])

object ApiAccess {
  def build(config: Option[Configuration]): ApiAccess = ApiAccess(
    `type` = APIAccessType.PRIVATE,
    whitelistedApplicationIds = config.flatMap(
                                  _.getOptional[Seq[String]]("whitelistedApplicationIds")
                                  .map(_.toList)
                                  .orElse(Some(List.empty[String]))
                                )
  )
}

object ApiStatus extends Enumeration {
  type APIStatus = Value
  val ALPHA, BETA, STABLE, DEPRECATED, RETIRED = Value
}

object APIAccessType extends Enumeration {
  type APIAccessType = Value
  val PRIVATE, PUBLIC = Value
}

case class ApiSubscription(name: String, serviceName: String, context: String, versions: List[VersionSubscription],
                           isTestSupport: Boolean = false)

object ApiSubscription {

  def from(apiDefinition: ApiDefinition, subscribedApis: List[ApiIdentifier]): ApiSubscription = {
    val versionSubscriptions: List[VersionSubscription] = apiDefinition.versions.map { v =>
      VersionSubscription(v, subscribedApis.exists(s => s.context == apiDefinition.context && s.version == v.version))
    }
    ApiSubscription(apiDefinition.name, apiDefinition.serviceName, apiDefinition.context, versionSubscriptions,
      apiDefinition.isTestSupport.getOrElse(false))
  }
}

case class VersionSubscription(version: ApiVersion, subscribed: Boolean)

case class SubscriptionData(apiIdentifier: ApiIdentifier, applications: Set[UUID])
