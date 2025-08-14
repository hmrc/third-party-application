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

import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

object ParamsValidator {
  import cats.implicits._

  def checkLastUsedParamsCombinations(params: List[Param[_]]): ErrorsOr[Unit] =
    params.filter(_.section == 5).sortBy(_.order) match {
      case LastUsedAfterQP(after) :: LastUsedBeforeQP(before) :: _ if after.isAfter(before) => "cannot query for used after date that is after a given before date".invalidNel
      case _                                                                                => ().validNel
    }

  def checkSubscriptionsParamsCombinations(params: List[Param[_]]): ErrorsOr[Unit] = {
    import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

    params.filter(_.section == 4).filterNot(_ == Param.WantSubscriptionsQP).sortBy(_.order) match {
      case NoSubscriptionsQP :: Nil                                 => ().validNel
      case HasSubscriptionsQP :: Nil                                => ().validNel
      case ApiContextQP(context) :: ApiVersionNbrQP(version) :: Nil => ().validNel
      case ApiContextQP(context) :: Nil                             => ().validNel

      case NoSubscriptionsQP :: HasSubscriptionsQP :: _ => "cannot query for no subscriptions and then query for subscriptions".invalidNel

      case NoSubscriptionsQP :: ApiContextQP(_) :: _    => "cannot query for no subscriptions and then query context".invalidNel
      case NoSubscriptionsQP :: ApiVersionNbrQP(_) :: _ => "cannot query for no subscriptions and then query version nbr".invalidNel

      case HasSubscriptionsQP :: ApiContextQP(_) :: _    => "cannot query for any subscriptions and then query context".invalidNel
      case HasSubscriptionsQP :: ApiVersionNbrQP(_) :: _ => "cannot query for any subscriptions and then query version nbr".invalidNel

      case ApiVersionNbrQP(_) :: _ => "cannot query for a version without a context".invalidNel

      case _ => ().validNel // "Unexpected combination of subscription query parameters".invalid
    }
  }

  def checkUniqueParamsCombinations(params: List[Param[_]]): ErrorsOr[Unit] = {
    params.partition(_.section == 1) match {
      case (Nil, Nil)               => "undefined queries are not permitted".invalidNel
      case (Nil, _)                 => ().validNel
      case (singleQueryParams, Nil) =>
        singleQueryParams.sortBy(_.order) match {
          case ServerTokenQP(serverToken) :: Nil                                       => ().validNel
          case ServerTokenQP(serverToken) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ().validNel
          case ServerTokenQP(serverToken) :: UserAgentQP(_) :: Nil                     => ().validNel
          case ServerTokenQP(_) :: _ :: Nil                                            => "serverToken can only be used with an optional userAgent".invalidNel

          case ClientIdQP(clientId) :: Nil                                       => ().validNel
          case ClientIdQP(clientId) :: UserAgentQP(`ApiGatewayUserAgent`) :: Nil => ().validNel
          case ClientIdQP(clientId) :: UserAgentQP(_) :: Nil                     => ().validNel
          case ClientIdQP(_) :: _ :: Nil                                         => "clientId can only be used with an optional userAgent".invalidNel

          case ApplicationIdQP(applicationId) :: Nil                   => ().validNel
          case ApplicationIdQP(applicationId) :: UserAgentQP(_) :: Nil => ().validNel
          case ApplicationIdQP(applicationId) :: _ :: Nil              => "applicationId cannot be mixed with any other parameters".invalidNel
          case _                                                       => "unexpected match for singe result parameters".invalidNel
        }
      case (_, _)                   => "queries with identifiers cannot be matched with other parameters, sorting or pagination".invalidNel
    }
  }

  def validateParamCombinations(allParams: List[Param[_]]): ErrorsOr[Unit] = {
    checkSubscriptionsParamsCombinations(allParams)
      .combine(checkLastUsedParamsCombinations(allParams))
      .combine(checkUniqueParamsCombinations(allParams))
  }

  def parseAndValidateParams(rawQueryParams: Map[String, Seq[String]], rawHeaders: Map[String, Seq[String]]): ErrorsOr[List[Param[_]]] = {
    val queryParams       = QueryParamValidator.parseParams(rawQueryParams)
    val headerParams      = HeaderValidator.parseHeaders(rawHeaders)
    val allParamsOrErrors = queryParams combine headerParams

    allParamsOrErrors.andThen(allParams => {
      validateParamCombinations(allParams)
        .map(_ => allParams)
    })
  }
}
