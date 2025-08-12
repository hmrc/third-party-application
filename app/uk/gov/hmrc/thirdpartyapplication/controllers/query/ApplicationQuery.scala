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

sealed trait ApplicationQuery

sealed trait SingleApplicationQuery extends ApplicationQuery

sealed trait MultipleApplicationQuery extends ApplicationQuery {
  def sort: Sorting
}

object ApplicationQuery {

  case class ById(applicationId: ApplicationId)                                                       extends SingleApplicationQuery
  case class ByClientId(clientId: ClientId, recordUsage: Boolean = false)                             extends SingleApplicationQuery
  case class ByServerToken(serverToken: String, recordUsage: Boolean)                                 extends SingleApplicationQuery
  case class GeneralOpenEndedApplicationQuery(sort: Sorting, params: List[Param[_]])                  extends MultipleApplicationQuery
  case class PaginatedApplicationQuery(sort: Sorting, pagination: Pagination, params: List[Param[_]]) extends MultipleApplicationQuery

  import cats.implicits._

  def identifyAnyPagination(allParams: List[Param[_]]): Option[Pagination] = {
    allParams.filter(_.section == 2) match {
      case PageSizeQP(size) :: PageNbrQP(nbr) :: Nil => Pagination(size, nbr).some
      case PageNbrQP(nbr) :: PageSizeQP(size) :: Nil => Pagination(size, nbr).some
      case PageSizeQP(size) :: Nil                   => Pagination(size, 1).some
      case PageNbrQP(nbr) :: Nil                     => Pagination(50, nbr).some
      case _                                         => None
    }
  }

  def identifySort(allParams: List[Param[_]]): Sorting = {
    allParams.filter(_.section == 3) match {
      case SortQP(sort) :: Nil => sort
      case _                   => Sorting.SubmittedAscending
    }
  }

  // List must be valid or outcome is undefined.
  def attemptToConstructQuery(validParams: List[Param[_]]): ApplicationQuery = {
    def attemptToConstructSingleResultQuery(): Option[SingleApplicationQuery] = {
      import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

      validParams.partition(_.section == 1) match {
        case (Nil, _)                 => None
        case (singleQueryParams, Nil) =>
          singleQueryParams.sortBy(_.order) match {
            case ServerTokenQP(serverToken) :: Nil                                       => ApplicationQuery.ByServerToken(serverToken, false).some
            case ServerTokenQP(serverToken) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ApplicationQuery.ByServerToken(serverToken, true).some
            case ServerTokenQP(serverToken) :: UserAgentQP(_) :: Nil                     => ApplicationQuery.ByServerToken(serverToken, false).some

            case ClientIdQP(clientId) :: Nil                                       => ApplicationQuery.ByClientId(clientId, false).some
            case ClientIdQP(clientId) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ApplicationQuery.ByClientId(clientId, true).some
            case ClientIdQP(clientId) :: UserAgentQP(_) :: Nil                     => ApplicationQuery.ByClientId(clientId, false).some

            case ApplicationIdQP(applicationId) :: Nil                   => ApplicationQuery.ById(applicationId).some
            case ApplicationIdQP(applicationId) :: UserAgentQP(_) :: Nil => ApplicationQuery.ById(applicationId).some
            case _                                                       => None
          }
        case _                        => None
      }
    }

    def attemptToConstructMultiResultQuery(): MultipleApplicationQuery = {
      val multiQueryParams = validParams.filter(_.section >= 4)

      val sorting = identifySort(validParams)

      identifyAnyPagination(validParams)
        .fold[MultipleApplicationQuery](
          ApplicationQuery.GeneralOpenEndedApplicationQuery(sorting, multiQueryParams)
        )(pagination =>
          ApplicationQuery.PaginatedApplicationQuery(sorting, pagination, multiQueryParams)
        )
    }

    attemptToConstructSingleResultQuery().fold[ApplicationQuery](
      attemptToConstructMultiResultQuery()
    )(identity)
  }
}
