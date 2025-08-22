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

sealed trait SingleApplicationQuery extends ApplicationQuery {
  def otherParams: List[NonUniqueFilterParam[_]]

  lazy val wantsSubscriptions: Boolean = ApplicationQueryConverter.wantsSubscriptions(otherParams)
}

sealed trait MultipleApplicationQuery extends ApplicationQuery {
  def sorting: Sorting
  def params: List[FilterParam[_]]
  lazy val hasAnySubscriptionFilter: Boolean      = ApplicationQueryConverter.hasAnySubscriptionFilter(params)
  lazy val hasSpecificSubscriptionFilter: Boolean = ApplicationQueryConverter.hasSpecificSubscriptionFilter(params)
  lazy val wantsSubscriptions: Boolean            = ApplicationQueryConverter.wantsSubscriptions(params)
}

object ApplicationQuery {

  case class ById protected (applicationId: ApplicationId, otherParams: List[NonUniqueFilterParam[_]])                       extends SingleApplicationQuery
  case class ByClientId protected (clientId: ClientId, recordUsage: Boolean, otherParams: List[NonUniqueFilterParam[_]])     extends SingleApplicationQuery
  case class ByServerToken protected (serverToken: String, recordUsage: Boolean, otherParams: List[NonUniqueFilterParam[_]]) extends SingleApplicationQuery

  case class GeneralOpenEndedApplicationQuery protected (params: List[NonUniqueFilterParam[_]], sorting: Sorting = Sorting.NoSorting) extends MultipleApplicationQuery

  case class PaginatedApplicationQuery protected (params: List[NonUniqueFilterParam[_]], sorting: Sorting = Sorting.NoSorting, pagination: Pagination = Pagination())
      extends MultipleApplicationQuery

  import cats.implicits._

  def identifyAnyPagination(allParams: List[Param[_]]): Option[Pagination] = {
    allParams.collect {
      case pp: PaginationParam[_] => pp
    }
      .sortBy(_.order) match {
      case PageSizeQP(size) :: PageNbrQP(nbr) :: Nil => Pagination(size, nbr).some
      case PageSizeQP(size) :: Nil                   => Pagination(pageSize = size).some
      case PageNbrQP(nbr) :: Nil                     => Pagination(pageNbr = nbr).some
      case _                                         => None
    }
  }

  def identifyAnySorting(params: List[Param[_]]): Sorting = {
    params.collect {
      case sp: SortingParam[_] => sp
    } match {
      case SortQP(sort) :: Nil => sort
      case _                   => Sorting.SubmittedAscending
    }
  }

  // List must be valid or outcome is undefined.
  def attemptToConstructQuery(validParams: List[Param[_]]): ApplicationQuery = {
    def attemptToConstructSingleResultQuery(nonUniqueFilterParam: List[NonUniqueFilterParam[_]]): Option[SingleApplicationQuery] = {
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
          case ServerTokenQP(serverToken)     => ApplicationQuery.ByServerToken(serverToken, hasApiGatewayUserAgent, nonUniqueFilterParam).some
          case ClientIdQP(clientId)           => ApplicationQuery.ByClientId(clientId, hasApiGatewayUserAgent, nonUniqueFilterParam).some
          case ApplicationIdQP(applicationId) => ApplicationQuery.ById(applicationId, nonUniqueFilterParam).some
        }
    }

    def attemptToConstructMultiResultQuery(nonUniqueFilterParam: List[NonUniqueFilterParam[_]]): MultipleApplicationQuery = {

      val sorting = ApplicationQuery.identifyAnySorting(validParams)

      identifyAnyPagination(validParams)
        .fold[MultipleApplicationQuery](
          ApplicationQuery.GeneralOpenEndedApplicationQuery(nonUniqueFilterParam, sorting)
        )(pagination => {
          ApplicationQuery.PaginatedApplicationQuery(nonUniqueFilterParam, sorting, pagination)
        })
    }

    val nonUniqueFilterParam = validParams.collect {
      case fp: NonUniqueFilterParam[_] => fp
    }

    attemptToConstructSingleResultQuery(nonUniqueFilterParam).fold[ApplicationQuery](
      attemptToConstructMultiResultQuery(nonUniqueFilterParam)
    )(identity)
  }
}
