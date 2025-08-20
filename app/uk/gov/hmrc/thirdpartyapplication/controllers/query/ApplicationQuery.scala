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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationQueryConverter

sealed trait ApplicationQuery

sealed trait SingleApplicationQuery extends ApplicationQuery

sealed trait MultipleApplicationQuery extends ApplicationQuery {
  def sorting: Sorting
  def params: List[FilterParam[_]]
  lazy val hasAnySubscriptionFilter: Boolean      = ApplicationQueryConverter.hasAnySubscriptionFilter(params)
  lazy val hasSpecificSubscriptionFilter: Boolean = ApplicationQueryConverter.hasSpecificSubscriptionFilter(params)
  lazy val wantsSubscriptions: Boolean            = ApplicationQueryConverter.wantsSubscriptions(params)
}

object ApplicationQuery {

  case class ById protected (applicationId: ApplicationId)                       extends SingleApplicationQuery
  case class ByClientId protected (clientId: ClientId, recordUsage: Boolean)     extends SingleApplicationQuery
  case class ByServerToken protected (serverToken: String, recordUsage: Boolean) extends SingleApplicationQuery

  case class GeneralOpenEndedApplicationQuery protected (sorting: Sorting, params: List[FilterParam[_]]) extends MultipleApplicationQuery

  case class PaginatedApplicationQuery protected (pagination: Pagination, sorting: Sorting, params: List[FilterParam[_]]) extends MultipleApplicationQuery

  import cats.implicits._

  def identifyAnyPagination(allParams: List[Param[_]]): Option[Pagination] = {
    allParams.collect {
      case pp: PaginationParam[_] => pp
    }
      .sortBy(_.order) match {
      case PageSizeQP(size) :: PageNbrQP(nbr) :: Nil => Pagination(size, nbr).some
      case PageSizeQP(size) :: Nil                   => Pagination(size, 1).some
      case PageNbrQP(nbr) :: Nil                     => Pagination(50, nbr).some
      case _                                         => None
    }
  }

  def identifySort(params: List[SortingParam[_]]): Sorting = {
    params match {
      case SortQP(sort) :: Nil => sort
      case _                   => Sorting.SubmittedAscending
    }
  }

  // List must be valid or outcome is undefined.
  def attemptToConstructQuery(validParams: List[Param[_]]): ApplicationQuery = {
    def attemptToConstructSingleResultQuery(): Option[SingleApplicationQuery] = {
      import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

      val hasApiGatewayUserAgent = validParams.find(_ match {
        case UserAgentQP(`ApiGatewayUserAgent`) => true
        case _                                  => false
      }).isDefined

      validParams.collect {
        case fp: UniqueFilterParam[_] => fp
      }
        .headOption // There can only be one
        .flatMap {
          case ServerTokenQP(serverToken)     => ApplicationQuery.ByServerToken(serverToken, hasApiGatewayUserAgent).some
          case ClientIdQP(clientId)           => ApplicationQuery.ByClientId(clientId, hasApiGatewayUserAgent).some
          case ApplicationIdQP(applicationId) => ApplicationQuery.ById(applicationId).some
        }
    }

    def attemptToConstructMultiResultQuery(): MultipleApplicationQuery = {
      val multiQueryParams = validParams.collect {
        case fp: FilterParam[_] => fp
      }

      val sortingParams = validParams.collect {
        case sp: SortingParam[_] => sp
      }

      val sorting = ApplicationQuery.identifySort(sortingParams)

      identifyAnyPagination(validParams)
        .fold[MultipleApplicationQuery](
          ApplicationQuery.GeneralOpenEndedApplicationQuery(sorting, multiQueryParams)
        )(pagination => {
          ApplicationQuery.PaginatedApplicationQuery(pagination, sorting, multiQueryParams)
        })
    }

    attemptToConstructSingleResultQuery().fold[ApplicationQuery](
      attemptToConstructMultiResultQuery()
    )(identity)
  }
}
