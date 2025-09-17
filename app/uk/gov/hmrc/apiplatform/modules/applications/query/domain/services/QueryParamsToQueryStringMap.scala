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

package uk.gov.hmrc.apiplatform.modules.applications.query.domain.services

import java.time.Instant
import java.time.format.DateTimeFormatter

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.Param._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models._

object QueryParamsToQueryStringMap {

  def toQuery(qry: ApplicationQuery): Map[String, Seq[String]] = {
    qry match {
      case s: SingleApplicationQuery           => toQuery(s)
      case g: GeneralOpenEndedApplicationQuery => toQuery(g)
      case p: PaginatedApplicationQuery        => toQuery(p)
    }
  }

  private def toQuery(qry: SingleApplicationQuery): Map[String, Seq[String]] = {
    qry match {
      case ById(id, other, wantSubscriptions)                => Map(ParamNames.ApplicationId -> Seq(id.toString)) ++ paramForWantSubs(wantSubscriptions)
      case ByClientId(clientId, _, other, wantSubscriptions) => Map(ParamNames.ClientId -> Seq(clientId.value)) ++ paramForWantSubs(wantSubscriptions)
      case ByServerToken(token, _, other, wantSubscriptions) => Map(ParamNames.ServerToken -> Seq(token)) ++ paramForWantSubs(wantSubscriptions)
    }
  }

  private def toQuery(qry: GeneralOpenEndedApplicationQuery): Map[String, Seq[String]] = {
    paramsFor(qry.params) ++ paramForSorting(qry.sorting) ++ paramForWantSubs(qry.wantSubscriptions)
  }

  private def toQuery(qry: PaginatedApplicationQuery): Map[String, Seq[String]] = {
    paramsFor(qry.params) ++ paramForSorting(qry.sorting) ++ paramsForPagination(qry.pagination)
  }

  private def paramValueForState(state: State): String = {
    state match {
      case State.TESTING                        => "CREATED"
      case State.PENDING_GATEKEEPER_APPROVAL    => "PENDING_GATEKEEPER_CHECK"
      case State.PENDING_REQUESTER_VERIFICATION => "PENDING_SUBMITTER_VERIFICATION"
      case s                                    => s.toString
    }
  }

  def paramForWantSubs(wantSubscriptions: Boolean): Map[String, Seq[String]] = {
    if (wantSubscriptions)
      Map(ParamNames.WantSubscriptions -> Seq.empty)
    else
      Map.empty
  }

  def paramForSorting(sort: Sorting): Map[String, Seq[String]] = {
    if (sort != Sorting.NoSorting)
      Map(ParamNames.Sort -> Seq(Sorting.asText(sort)))
    else
      Map.empty
  }

  def paramsForPagination(pagination: Pagination): Map[String, Seq[String]] = {
    import Pagination.Defaults._

    pagination match {
      // We always need to ensure one param is returned even if it's all defaulted, as this indicates a paginated query
      case Pagination(PageSize, PageNbr) => Map(ParamNames.PageNbr -> Seq("1"))
      case Pagination(sz, PageNbr)       => Map(ParamNames.PageSize -> Seq(sz.toString))
      case Pagination(PageSize, nbr)     => Map(ParamNames.PageNbr -> Seq(nbr.toString))
      case Pagination(sz, nbr)           => Map(ParamNames.PageSize -> Seq(sz.toString), ParamNames.PageNbr -> Seq(nbr.toString))
    }
  }

  private def paramValueForInstant(instant: Instant): String = {
    DateTimeFormatter.ISO_INSTANT.format(instant)
  }

  private def paramsFor(params: List[NonUniqueFilterParam[_]]): Map[String, Seq[String]] = {
    import cats.syntax.option._

    params.map(_ match {
      case _: UserAgentParam[_] => None

      case NoSubscriptionsQP           => (ParamNames.NoSubscriptions   -> Seq.empty).some
      case HasSubscriptionsQP          => (ParamNames.HasSubscriptions  -> Seq.empty).some
      case ApiContextQP(value)         => (ParamNames.ApiContext        -> Seq(value.toString())).some
      case ApiVersionNbrQP(value)      => (ParamNames.ApiVersionNbr     -> Seq(value.toString())).some
      case LastUsedAfterQP(value)      => (ParamNames.LastUsedAfter     -> Seq(paramValueForInstant(value))).some
      case LastUsedBeforeQP(value)     => (ParamNames.LastUsedBefore    -> Seq(paramValueForInstant(value))).some
      case UserIdQP(value)             => (ParamNames.UserId            -> Seq(value.toString())).some
      case EnvironmentQP(value)        => (ParamNames.Environment       -> Seq(value.toString())).some
      case IncludeDeletedQP            => (ParamNames.IncludeDeleted    -> Seq.empty).some
      case NoRestrictionQP             => (ParamNames.DeleteRestriction -> Seq("NO_RESTRICTION")).some
      case DoNotDeleteQP               => (ParamNames.DeleteRestriction -> Seq("DO_NOT_DELETE")).some
      case ActiveStateQP               => (ParamNames.Status            -> Seq("ACTIVE")).some
      case ExcludeDeletedQP            => (ParamNames.Status            -> Seq("EXCLUDING_DELETED")).some
      case BlockedStateQP              => (ParamNames.Status            -> Seq("BLOCKED")).some
      case NoStateFilteringQP          => (ParamNames.Status            -> Seq("ANY")).some
      case MatchOneStateQP(state)      => (ParamNames.Status            -> Seq(paramValueForState(state))).some
      case MatchManyStatesQP(states)   => (ParamNames.Status            -> states.toList.map(paramValueForState)).some
      case AppStateBeforeDateQP(value) => (ParamNames.StatusDateBefore  -> Seq(paramValueForInstant(value))).some
      case SearchTextQP(value)         => (ParamNames.Search            -> Seq(value)).some
      case NameQP(value)               => (ParamNames.Name              -> Seq(value)).some
      case VerificationCodeQP(value)   => (ParamNames.VerificationCode  -> Seq(value)).some
      case MatchAccessTypeQP(value)    => (ParamNames.AccessType        -> Seq(value.toString)).some
      case AnyAccessTypeQP             => (ParamNames.AccessType        -> Seq("ANY")).some
    }).collect {
      case Some(x) => x
    }.toMap
  }
}
