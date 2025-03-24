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

  trait SingleApplicationQuery extends ApplicationQuery

  case class ById(applicationId: ApplicationId) extends SingleApplicationQuery

  case class ByClientId(clientId: ClientId, recordUsage: Boolean = false) extends SingleApplicationQuery
  case class ByServerToken(serverToken: String, recordUsage: Boolean)     extends SingleApplicationQuery

  trait MultipleApplicationQuery  extends ApplicationQuery
  trait OpenEndedApplicationQuery extends MultipleApplicationQuery

  case class UserApplicationQuery(userId: UserId, environment: Environment)  extends OpenEndedApplicationQuery
  case class ByContext(context: ApiContext)                                  extends OpenEndedApplicationQuery
  case class ByApiIdentifier(context: ApiContext, versionNbr: ApiVersionNbr) extends OpenEndedApplicationQuery
  case object NoSubscriptions                                                extends OpenEndedApplicationQuery

  trait PaginatedApplicationQuery extends MultipleApplicationQuery {
    def pageNbr: Int
    def pageSize: Int
  }

  import cats.implicits._

  def constructQueryIfCombinationsAreValid(params: List[Param[_]]): ErrorsOr[ApplicationQuery] = {
    import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

    params.sortBy(_.order) match {
      case ServerTokenQP(serverToken) :: Nil                                       => ApplicationQuery.ByServerToken(serverToken, false).validNel
      case ServerTokenQP(serverToken) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ApplicationQuery.ByServerToken(serverToken, true).validNel
      case ServerTokenQP(serverToken) :: UserAgentQP(_) :: Nil                     => ApplicationQuery.ByServerToken(serverToken, false).validNel
      case ServerTokenQP(_) :: _                                            => "serverToken can only be used with an optional userAgent".invalidNel

      case ClientIdQP(clientId) :: Nil                                       => ApplicationQuery.ByClientId(clientId, false).validNel
      case ClientIdQP(clientId) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ApplicationQuery.ByClientId(clientId, true).validNel
      case ClientIdQP(clientId) :: UserAgentQP(_) :: Nil                     => ApplicationQuery.ByClientId(clientId, false).validNel
      case ClientIdQP(_) :: _                                         => "clientId can only be used with an optional userAgent".invalidNel

      case ApplicationIdQP(applicationId) :: Nil      => ApplicationQuery.ById(applicationId).validNel
      case ApplicationIdQP(applicationId) :: _        => "applicationId cannot be mixed with any other parameters".invalidNel

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
