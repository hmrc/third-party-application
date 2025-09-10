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

import scala.reflect.ClassTag

import cats.data.NonEmptyList

import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

object ParamsValidator {
  import cats.implicits._

  def first[T <: Param[_]](implicit params: List[Param[_]], ct: ClassTag[T]): Option[T] = params.collect {
    case qp: T => qp
  }.headOption

  def checkLastUsedParamsCombinations(params: List[NonUniqueFilterParam[_]]): ErrorsOr[Unit] =
    params.collect {
      case qp: LastUsedAfterQP  => qp
      case qp: LastUsedBeforeQP => qp
    } match {
      case LastUsedAfterQP(after) :: LastUsedBeforeQP(before) :: _ if after.isAfter(before) => "Cannot query for used after date that is after a given before date".invalidNel
      case _                                                                                => ().validNel
    }

  def checkSubscriptionsParamsCombinations(params: List[NonUniqueFilterParam[_]]): ErrorsOr[Unit] = {
    import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

    params.collect {
      case qp: SubscriptionFilterParam[_] => qp
    }
      .sortBy(_.order) match {
      case NoSubscriptionsQP :: Nil                                 => ().validNel
      case HasSubscriptionsQP :: Nil                                => ().validNel
      case ApiContextQP(context) :: ApiVersionNbrQP(version) :: Nil => ().validNel
      case ApiContextQP(context) :: Nil                             => ().validNel

      case NoSubscriptionsQP :: HasSubscriptionsQP :: _ => "Cannot query for no subscriptions and then query for subscriptions".invalidNel

      case NoSubscriptionsQP :: ApiContextQP(_) :: _    => "Cannot query for no subscriptions and then query context".invalidNel
      case NoSubscriptionsQP :: ApiVersionNbrQP(_) :: _ => "Cannot query for no subscriptions and then query version nbr".invalidNel

      case HasSubscriptionsQP :: ApiContextQP(_) :: _    => "Cannot query for any subscriptions and then query context".invalidNel
      case HasSubscriptionsQP :: ApiVersionNbrQP(_) :: _ => "Cannot query for any subscriptions and then query version nbr".invalidNel

      case ApiVersionNbrQP(_) :: _ => "Cannot query for a version without a context".invalidNel

      case _ => ().validNel
    }
  }

  def checkUniqueParamsCombinations(uniqueFilterParams: NonEmptyList[UniqueFilterParam[_]], otherFilterParams: List[NonUniqueFilterParam[_]]): ErrorsOr[Unit] = {
    // Cannot have more than one unique filter param
    // Cannot have a unqiue filter param and other filter params other than UserAgentQP or WantSubscriptions

    val onlyHasAllowableOtherParams = otherFilterParams.find(_ match {
      case WantSubscriptionsQP   => false
      case GenericUserAgentQP(_) => false
      case ApiGatewayUserAgentQP => false
      case _                     => true
    }).fold[ErrorsOr[Unit]](().validNel)(f => "Cannot mix unqiue and non-unique filter params".invalidNel)

    (uniqueFilterParams.head, uniqueFilterParams.tail.isEmpty) match {
      case (head, true) => onlyHasAllowableOtherParams
      case _            => "Cannot mix one or more unique query params (serverToken, clientId and applicationId)".invalidNel
    }
  }

  def checkVerificationCodeUsesDeleteExclusion(implicit otherFilterParams: List[NonUniqueFilterParam[_]]): ErrorsOr[Unit] = {
    (first[VerificationCodeQP], first[AppStateFilterQP]) match {
      case (None, _)                                                                              => ().validNel
      case (Some(VerificationCodeQP(_)), Some(AppStateFilterQP(AppStateFilter.ExcludingDeleted))) => ().validNel
      case (Some(VerificationCodeQP(_)), _)                                                       => "Verification code queries must exclude deleted state".invalidNel
    }
  }

  def checkWantSubscriptions(wantSubcriptions: Boolean, wantPagination: Boolean): ErrorsOr[Unit] =
    if (wantSubcriptions && wantPagination) {
      "Cannot return subscriptions with paginated queries".invalidNel
    } else {
      ().validNel
    }

  def checkAppStateFilters(implicit otherFilterParams: List[NonUniqueFilterParam[Any]]): ErrorsOr[Unit] = {
    val stateFilter = first[AppStateFilterQP]
    val dateFilter  = first[AppStateBeforeDateQP]

    (stateFilter, dateFilter) match {
      case (_, None) => ().validNel

      case (Some(AppStateFilterQP(AppStateFilter.MatchingOne(_))), _) => ().validNel
      case (None, Some(_))                                            => "Cannot query state used before date without a state filter".invalidNel
      case _                                                          => "Cannot query state used before date without a single state filter".invalidNel
    }
  }

  def validateParamCombinations(implicit allParams: List[Param[_]]): ErrorsOr[Unit] = {
    val wantSubcriptions = first[WantSubscriptionsQP.type].headOption.isDefined

    val otherFilterParams  = allParams.collect {
      case fp: NonUniqueFilterParam[_] => fp
    }
    val uniqueFilterParams = allParams.collect(_ match {
      case ufp: UniqueFilterParam[_] => ufp
    })
    val sortingParams      = allParams.collect(_ match {
      case sp: SortingParam[_] => sp
    })
    val paginationParams   = allParams.collect(_ match {
      case pp: PaginationParam[_] => pp
    })
    val wantPagination     = paginationParams.headOption.isDefined

    ((uniqueFilterParams, otherFilterParams, sortingParams, paginationParams) match {
      case (Nil, Nil, Nil, Nil)  => ().validNel // Only GK
      case (Nil, Nil, _, _)      => ().validNel
      case (Nil, _, _, _)        => ().validNel
      case (u, Nil, Nil, Nil)    => ().validNel
      case (h :: t, f, Nil, Nil) => checkUniqueParamsCombinations(NonEmptyList(h, t), f)
      case (h :: t, f, _, _)     => checkUniqueParamsCombinations(NonEmptyList(h, t), f) combine "Cannot mix unique queries with sorting or pagination".invalidNel
    })
      .combine(checkSubscriptionsParamsCombinations(otherFilterParams))
      .combine(checkLastUsedParamsCombinations(otherFilterParams))
      .combine(checkVerificationCodeUsesDeleteExclusion(otherFilterParams))
      .combine(checkWantSubscriptions(wantSubcriptions, wantPagination))
      .combine(checkAppStateFilters(otherFilterParams))
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
