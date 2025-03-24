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
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param.PageSizeQP
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param.PageNbrQP

// Determine QRY - request => Either[Errors, QRY]
// Extract queryStrings
// Validate QS for bad parameter names => BadRequest
// ?? Normalise QS param names
// Extract headers (INTERNAL_USER_AGENT, SERVER_TOKEN_HEADER)
// Validate headers - only allowed once each etc
// Validate QS values (userId, appId etc)
// Combine into QRY
// Determine QRY return type
// Validate QRY for bad combinations => BadRequest

// Run QRY

// Map Result(s) to appropriate type

trait ApplicationQuery

// Request => Error | ApplicationQuery

object ApplicationQuery {
  val ApiGatewayUserAgent: String = "APIPlatformAuthorizer"

  case class Pagination(pageSize: Int, pageNbr: Int)


  trait SingleApplicationQuery extends ApplicationQuery

  case class ById(applicationId: ApplicationId) extends SingleApplicationQuery

  case class ByClientId(clientId: ClientId, recordUsage: Boolean = false) extends SingleApplicationQuery
  case class ByServerToken(serverToken: String, recordUsage: Boolean)     extends SingleApplicationQuery

  trait MultipleApplicationQuery  extends ApplicationQuery
  trait OpenEndedApplicationQuery extends MultipleApplicationQuery

  case class UserApplicationQuery(userId: UserId, environment: Environment)  extends OpenEndedApplicationQuery
  case class ByContext(context: ApiContext)                                  extends OpenEndedApplicationQuery
  case class ByApiIdentifier(apiIdentifier: ApiIdentifier)                   extends OpenEndedApplicationQuery
  case object WithNoSubscriptions                                            extends OpenEndedApplicationQuery

  trait PaginatedApplicationQuery extends MultipleApplicationQuery {
    def pageNbr: Int
    def pageSize: Int
  }

  import cats.implicits._

  def asPagination(params: List[Param[_]]): Option[Pagination] = {
    params match {
      case PageSizeQP(size) :: PageNbrQP(nbr) :: Nil => Pagination(size, nbr).some
      case PageSizeQP(size) :: Nil => Pagination(size, 1).some
      case PageNbrQP(nbr) :: Nil => Pagination(20, nbr).some
      case _ => None
    }
  }

  def extractPagination(params: List[Param[_]]): (Option[ApplicationQuery.Pagination], List[Param[_]]) = {
    params.partition(p => p.paramName == "pageSize" || p.paramName == "pageNbr")
    .leftMap(asPagination(_))
  }

  def constructQueryIfCombinationsAreValid(params: List[Param[_]]): ErrorsOr[ApplicationQuery] = {
    import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

    extractPagination(params.sortBy(_.order)) match {
      case (None, ServerTokenQP(serverToken) :: Nil)                                        => ApplicationQuery.ByServerToken(serverToken, false).validNel
      case (None, ServerTokenQP(serverToken) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil)  => ApplicationQuery.ByServerToken(serverToken, true).validNel
      case (None, ServerTokenQP(serverToken) :: UserAgentQP(_) :: Nil )                     => ApplicationQuery.ByServerToken(serverToken, false).validNel
      case (None, ServerTokenQP(_) :: _ :: Nil)                                             => "serverToken can only be used with an optional userAgent".invalidNel
      case (Some(_), ServerTokenQP(_) :: _)                                                 => "pagination not allowed with server token".invalidNel

      case (None, ClientIdQP(clientId) :: Nil)                                              => ApplicationQuery.ByClientId(clientId, false).validNel
      case (None, ClientIdQP(clientId) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil)        => ApplicationQuery.ByClientId(clientId, true).validNel
      case (None, ClientIdQP(clientId) :: UserAgentQP(_) :: Nil)                            => ApplicationQuery.ByClientId(clientId, false).validNel
      case (None, ClientIdQP(_) :: _ :: Nil)                                                => "clientId can only be used with an optional userAgent".invalidNel
      case (Some(_), ClientIdQP(_) :: _)                                                    => "pagination not allowed with clientId".invalidNel

      case (None, ApplicationIdQP(applicationId) :: Nil)                                    => ApplicationQuery.ById(applicationId).validNel
      case (None, ApplicationIdQP(applicationId) :: _ :: Nil)                               => "applicationId cannot be mixed with any other parameters".invalidNel
      case (Some(_), ApplicationIdQP(_) :: _)                                               => "pagination not allowed with applicationId".invalidNel

      case (_, NoSubscriptionsQP :: ApiContextQP(_) :: _)                                   => "cannot query for no subscriptions and then query context".invalidNel
      case (_, NoSubscriptionsQP :: ApiVersionNbrQP(_) :: _)                                => "cannot query for no subscriptions and then query version nbr".invalidNel
      case (_, ApiVersionNbrQP(_) :: _)                                                     => "cannot query for a version without a context".invalidNel
      case (maybePagination, NoSubscriptionsQP :: Nil)                                      => ApplicationQuery.WithNoSubscriptions.validNel
      case (maybePagination, ApiContextQP(context) :: ApiVersionNbrQP(version) :: Nil)      => ApplicationQuery.ByApiIdentifier(ApiIdentifier(context, version)).validNel
      case (maybePagination, ApiContextQP(context) :: Nil)                                  => ApplicationQuery.ByContext(context).validNel

      case (None, UserIdQP(userId) :: EnvironmentQP(environment) :: Nil)                    => ApplicationQuery.UserApplicationQuery(userId, environment).validNel
      case (None, UserIdQP(_) :: _ :: Nil)                                                  => "cannot query for userId without environment".invalidNel
      case (Some(_), UserIdQP(_) :: _ :: Nil)                                               => "pagination not allowed with userId".invalidNel

      case (None, _)                                                                        => "query without pagination is prohibited".invalidNel

      case (Some(pagination), EnvironmentQP(_) :: Nil)                                      => "cannot query for userId or environment without the other".invalidNel                            // Maybe ok


      case _ => "Bad combination".invalidNel
    }

  }

  def topLevel(rawQueryParams: Map[String, Seq[String]], rawHeaders: Map[String, Seq[String]]): ErrorsOr[ApplicationQuery] = {
    val queryParams  = QueryParamValidator.parseParams(rawQueryParams)
    val headerParams = HeaderValidator.parseHeaders(rawHeaders)

    val allParams = queryParams <+> headerParams
    allParams.andThen(constructQueryIfCombinationsAreValid)
  }

}
