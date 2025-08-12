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
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param.{LastUsedBeforeQP, _}

sealed trait ApplicationQuery

sealed trait SingleApplicationQuery extends ApplicationQuery

sealed trait MultipleApplicationQuery extends ApplicationQuery {
  def sort: Sorting
}

object ApplicationQuery {
  val ApiGatewayUserAgent: String = "APIPlatformAuthorizer"

  case class ById(applicationId: ApplicationId)                                                       extends SingleApplicationQuery
  case class ByClientId(clientId: ClientId, recordUsage: Boolean = false)                             extends SingleApplicationQuery
  case class ByServerToken(serverToken: String, recordUsage: Boolean)                                 extends SingleApplicationQuery
  case class GeneralOpenEndedApplicationQuery(sort: Sorting, params: List[Param[_]])                  extends MultipleApplicationQuery
  case class PaginatedApplicationQuery(sort: Sorting, pagination: Pagination, params: List[Param[_]]) extends MultipleApplicationQuery

  import cats.implicits._

  def checkSubscriptionsParams(params: List[Param[_]]): ErrorOr[Unit] = {
    import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

    params.filter(_.section == 4).sortBy(_.order) match {
      case NoSubscriptionsQP :: Nil                                 => ().valid
      case HasSubscriptionsQP :: Nil                                => ().valid
      case ApiContextQP(context) :: ApiVersionNbrQP(version) :: Nil => ().valid
      case ApiContextQP(context) :: Nil                             => ().valid

      case NoSubscriptionsQP :: HasSubscriptionsQP :: _ => "cannot query for no subscriptions and then query for subscriptions".invalid

      case NoSubscriptionsQP :: ApiContextQP(_) :: _    => "cannot query for no subscriptions and then query context".invalid
      case NoSubscriptionsQP :: ApiVersionNbrQP(_) :: _ => "cannot query for no subscriptions and then query version nbr".invalid

      case HasSubscriptionsQP :: ApiContextQP(_) :: _    => "cannot query for any subscriptions and then query context".invalid
      case HasSubscriptionsQP :: ApiVersionNbrQP(_) :: _ => "cannot query for any subscriptions and then query version nbr".invalid

      case ApiVersionNbrQP(_) :: _ => "cannot query for a version without a context".invalid

      case _ => ().valid // "Unexpected combination of subscription query parameters".invalid
    }
  }

  def checkLastUsedParams(params: List[Param[_]]): ErrorOr[Unit] =
    params.filter(_.section == 5).sortBy(_.order) match {
      case LastUsedAfterQP(after) :: LastUsedBeforeQP(before) :: _ if after.isAfter(before) => "cannot query for used after date that is after a given before date".invalid
      case _                                                                                => ().valid
    }

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

  def attemptToConstructQuery(rawQueryParams: Map[String, Seq[String]], rawHeaders: Map[String, Seq[String]]): ErrorsOr[ApplicationQuery] = {
    def attemptToConstructSingleResultQuery(validParams: List[Param[_]]): ErrorOr[Option[SingleApplicationQuery]] = {
      import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

      validParams.partition(_.section == 1) match {
        case (Nil, Nil)               => "undefined queries are not permitted".invalid
        case (Nil, _)                 => None.valid
        case (singleQueryParams, Nil) =>
          singleQueryParams.sortBy(_.order) match {
            case ServerTokenQP(serverToken) :: Nil                                       => ApplicationQuery.ByServerToken(serverToken, false).some.valid
            case ServerTokenQP(serverToken) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ApplicationQuery.ByServerToken(serverToken, true).some.valid
            case ServerTokenQP(serverToken) :: UserAgentQP(_) :: Nil                     => ApplicationQuery.ByServerToken(serverToken, false).some.valid
            case ServerTokenQP(_) :: _ :: Nil                                            => "serverToken can only be used with an optional userAgent".invalid

            case ClientIdQP(clientId) :: Nil                                       => ApplicationQuery.ByClientId(clientId, false).some.valid
            case ClientIdQP(clientId) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ApplicationQuery.ByClientId(clientId, true).some.valid
            case ClientIdQP(clientId) :: UserAgentQP(_) :: Nil                     => ApplicationQuery.ByClientId(clientId, false).some.valid
            case ClientIdQP(_) :: _ :: Nil                                         => "clientId can only be used with an optional userAgent".invalid

            case ApplicationIdQP(applicationId) :: Nil      => ApplicationQuery.ById(applicationId).some.valid
            case ApplicationIdQP(applicationId) :: _ :: Nil => "applicationId cannot be mixed with any other parameters".invalid
            case _                                          => "unexpected match for singe result parameters".invalid
          }
        case (_, _)                   => "queries with identifiers cannot be matched with other parameters, sorting or pagination".invalid
      }
    }

    def attemptToConstructMultiResultQuery(validParams: List[Param[_]]): ErrorsOr[MultipleApplicationQuery] = {
      val multiQueryParams = validParams.filter(_.section >= 4)

      checkSubscriptionsParams(validParams)
        .combine(checkLastUsedParams(validParams))
        .toValidatedNel andThen { _: Unit =>
        val sorting = identifySort(validParams)

        identifyAnyPagination(validParams)
          .fold[MultipleApplicationQuery](
            ApplicationQuery.GeneralOpenEndedApplicationQuery(sorting, multiQueryParams)
          )(pagination =>
            ApplicationQuery.PaginatedApplicationQuery(sorting, pagination, multiQueryParams)
          )
          .validNel
      }
    }

    val queryParams  = QueryParamValidator.parseParams(rawQueryParams)
    val headerParams = HeaderValidator.parseHeaders(rawHeaders)
    val allParams    = queryParams combine headerParams

    allParams.andThen(validParams => {
      attemptToConstructSingleResultQuery(validParams).toValidatedNel andThen { q: Option[SingleApplicationQuery] =>
        q.fold[ErrorsOr[ApplicationQuery]](
          attemptToConstructMultiResultQuery(validParams)
        )(
          _.validNel
        )
      }
    })
  }
}
