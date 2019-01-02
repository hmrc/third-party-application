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

package uk.gov.hmrc.models

import java.util.UUID

import play.api.Configuration
import play.api.libs.json._
import uk.gov.hmrc.models.APIStatus.APIStatus

case class APIDefinition(serviceName: String,
                         name: String,
                         context: String,
                         versions: Seq[APIVersion],
                         requiresTrust: Option[Boolean],
                         isTestSupport: Option[Boolean] = None)

case class APIVersion(version: String,
                      status: APIStatus,
                      access: Option[APIAccess])

case class APIAccess(`type`: APIAccessType.Value, whitelistedApplicationIds: Option[Seq[String]])

object APIAccess {
  def build(config: Option[Configuration]): APIAccess = APIAccess(
    `type` = APIAccessType.PRIVATE,
    whitelistedApplicationIds = config.flatMap(_.getStringSeq("whitelistedApplicationIds")).orElse(Some(Seq.empty)))
}

object APIStatus extends Enumeration {
  type APIStatus = Value
  val ALPHA, BETA, STABLE, DEPRECATED, RETIRED = Value
}

object APIAccessType extends Enumeration {
  type APIAccessType = Value
  val PRIVATE, PUBLIC = Value
}

case class APISubscription(name: String, serviceName: String, context: String, versions: Seq[VersionSubscription],
                           requiresTrust: Option[Boolean], isTestSupport: Boolean = false)

object APISubscription {

  def from(apiDefinition: APIDefinition, subscribedApis: Seq[APIIdentifier]): APISubscription = {
    val versionSubscriptions: Seq[VersionSubscription] = apiDefinition.versions.map { v =>
      VersionSubscription(v, subscribedApis.exists(s => s.context == apiDefinition.context && s.version == v.version))
    }
    APISubscription(apiDefinition.name, apiDefinition.serviceName, apiDefinition.context, versionSubscriptions,
      apiDefinition.requiresTrust, apiDefinition.isTestSupport.getOrElse(false))
  }
}

case class VersionSubscription(version: APIVersion, subscribed: Boolean)

case class SubscriptionData(apiIdentifier: APIIdentifier, applications: Set[UUID])
