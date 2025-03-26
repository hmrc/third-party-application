/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers.query

import java.time.Instant

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.{AccessTypeFilter, ApplicationSort, DeleteRestrictionFilter, StatusFilter}

sealed trait Param[+P] {
  def order: Int
  def section: Int
}

object Param {
  case class ServerTokenQP(value: String)          extends Param[String]        { val section = 1; val order = 1 }
  case class ClientIdQP(value: ClientId)           extends Param[ClientId]      { val section = 1; val order = 2 }
  case class UserAgentQP(value: String)            extends Param[String]        { val section = 1; val order = 3 }
  case class ApplicationIdQP(value: ApplicationId) extends Param[ApplicationId] { val section = 1; val order = 4 }

  case class PageSizeQP(value: Int) extends Param[Int] { val section = 2; val order = 1 }
  case class PageNbrQP(value: Int)  extends Param[Int] { val section = 2; val order = 2 }

  case class SortQP(value: ApplicationSort) extends Param[ApplicationSort] { val section = 3; val order = 200 }

  case object NoSubscriptionsQP                    extends Param[Unit]          { val section = 4; val order = 1 }
  case object HasSubscriptionsQP                   extends Param[Unit]          { val section = 4; val order = 2 }
  case class ApiContextQP(value: ApiContext)       extends Param[ApiContext]    { val section = 4; val order = 3 }
  case class ApiVersionNbrQP(value: ApiVersionNbr) extends Param[ApiVersionNbr] { val section = 4; val order = 4 }

  case class UserIdQP(value: UserId)           extends Param[UserId]      { val section = 5; val order = 1 }
  case class EnvironmentQP(value: Environment) extends Param[Environment] { val section = 5; val order = 1 }

  case class StatusFilterQP(value: StatusFilter)   extends Param[StatusFilter]     { val section = 5; val order = 1 }
  case class AccessTypeQP(value: AccessTypeFilter) extends Param[AccessTypeFilter] { val section = 5; val order = 1 }

  case class SearchTextQP(value: String)                         extends Param[String]                  { val section = 5; val order = 1 }
  case class IncludeDeletedQP(value: Boolean)                    extends Param[Boolean]                 { val section = 5; val order = 1 }
  case class DeleteRestrictionQP(value: DeleteRestrictionFilter) extends Param[DeleteRestrictionFilter] { val section = 5; val order = 1 }
  case class LastUsedBeforeQP(value: Instant)                    extends Param[Instant]                 { val section = 5; val order = 1 }
  case class LastUsedAfterQP(value: Instant)                     extends Param[Instant]                 { val section = 5; val order = 1 }
}
