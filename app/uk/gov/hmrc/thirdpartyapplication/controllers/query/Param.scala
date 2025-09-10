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
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType

/*
 * Param is used to store validated (singularly and in combo) values for queries
 */
sealed trait Param[+P]

sealed trait FilterParam[+P] extends Param[P]

sealed trait UniqueFilterParam[+P] extends FilterParam[P]

sealed trait NonUniqueFilterParam[+P] extends FilterParam[P]

sealed trait PaginationParam[+P] extends Param[P] { def order: Int }

sealed trait SortingParam[+P] extends Param[P]

object Param {
  case class ServerTokenQP(value: String)          extends UniqueFilterParam[String]
  case class ClientIdQP(value: ClientId)           extends UniqueFilterParam[ClientId]
  case class ApplicationIdQP(value: ApplicationId) extends UniqueFilterParam[ApplicationId]

  sealed trait UserAgentParam[T]               extends NonUniqueFilterParam[T]
  case class GenericUserAgentQP(value: String) extends UserAgentParam[String]
  case object ApiGatewayUserAgentQP            extends UserAgentParam[Unit]

  case class PageSizeQP(value: Int) extends PaginationParam[Int] { val order = 1 }
  case class PageNbrQP(value: Int)  extends PaginationParam[Int] { val order = 2 }

  case class SortQP(value: Sorting) extends SortingParam[Sorting]

  sealed trait SubscriptionFilterParam[T]          extends NonUniqueFilterParam[T]                { def order: Int }
  case object NoSubscriptionsQP                    extends SubscriptionFilterParam[Unit]          { val order = 1  }
  case object HasSubscriptionsQP                   extends SubscriptionFilterParam[Unit]          { val order = 2  }
  case class ApiContextQP(value: ApiContext)       extends SubscriptionFilterParam[ApiContext]    { val order = 3  }
  case class ApiVersionNbrQP(value: ApiVersionNbr) extends SubscriptionFilterParam[ApiVersionNbr] { val order = 4  }

  case class LastUsedAfterQP(value: Instant)  extends NonUniqueFilterParam[Instant]
  case class LastUsedBeforeQP(value: Instant) extends NonUniqueFilterParam[Instant]

  case object WantSubscriptionsQP    extends NonUniqueFilterParam[Unit]
  case class UserIdQP(value: UserId) extends NonUniqueFilterParam[UserId]

  case class EnvironmentQP(value: Environment) extends NonUniqueFilterParam[Environment]

  sealed trait IncludeOrExcludeDeletedQP extends NonUniqueFilterParam[Unit]
  object IncludeDeletedQP                extends IncludeOrExcludeDeletedQP
  object ExcludeDeletedQP                extends IncludeOrExcludeDeletedQP

  sealed trait DeleteRestrictionQP extends NonUniqueFilterParam[Unit]
  case object NoRestrictionQP      extends DeleteRestrictionQP
  case object DoNotDeleteQP        extends DeleteRestrictionQP

  case class AppStateFilterQP(value: AppStateFilter) extends NonUniqueFilterParam[AppStateFilter]
  case class AppStateBeforeDateQP(value: Instant)    extends NonUniqueFilterParam[Instant]
  case class SearchTextQP(value: String)             extends NonUniqueFilterParam[String]
  case class NameQP(value: String)                   extends NonUniqueFilterParam[String]
  case class VerificationCodeQP(value: String)       extends NonUniqueFilterParam[String]

  sealed trait AccessTypeParam[T]                 extends NonUniqueFilterParam[T]
  case class MatchAccessTypeQP(value: AccessType) extends AccessTypeParam[AccessType]
  case object AnyAccessTypeQP                     extends AccessTypeParam[Unit]
}
