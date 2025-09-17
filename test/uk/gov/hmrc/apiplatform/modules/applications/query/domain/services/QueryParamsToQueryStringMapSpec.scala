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

import cats.data.NonEmptyList
import org.scalatest.EitherValues

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApiIdentifierFixtures, ClientIdFixtures, Environment, UserIdFixtures}
import uk.gov.hmrc.apiplatform.modules.common.utils.{FixedClock, HmrcSpec}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.Param._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.{ApplicationQueries, ApplicationQuery, Pagination, ParamNames, Sorting}

class QueryParamsToQueryStringMapSpec extends HmrcSpec with EitherValues with ClientIdFixtures with UserIdFixtures with ApiIdentifierFixtures with FixedClock {

  def test(qry: ApplicationQuery, map: Map[String, Seq[String]]): Unit = {
    QueryParamsToQueryStringMap.toQuery(qry) shouldBe map
  }

  def test(qry: ApplicationQuery, pairs: (String, String)*): Unit = {
    test(qry, Map.empty[String, Seq[String]] ++ pairs.map(p => p._1 -> Seq(p._2)))
  }

  def testOfNoValue(qry: ApplicationQuery, param: String): Unit = {
    QueryParamsToQueryStringMap.toQuery(qry) shouldBe Map(param -> Seq.empty)
  }

  "allApplications" should {
    "convert to query" in {
      test(ApplicationQueries.allApplications(true), ParamNames.Status -> "EXCLUDING_DELETED")
      test(ApplicationQueries.allApplications(false))
    }
  }

  "applicationByClientId" should {
    "convert to query" in {
      test(ApplicationQueries.applicationByClientId(clientIdOne), ParamNames.ClientId -> (clientIdOne.value))
    }
  }

  "applicationsByName" should {
    "convert to query" in {
      test(ApplicationQueries.applicationsByName("bob"), ParamNames.Name -> "bob", ParamNames.Environment -> "PRODUCTION", ParamNames.Status -> "EXCLUDING_DELETED")
    }
  }

  "applicationsByVerifiableUplift" should {
    "convert to query" in {
      test(ApplicationQueries.applicationsByVerifiableUplift("bob"), ParamNames.VerificationCode -> "bob", ParamNames.Status -> "EXCLUDING_DELETED")
    }
  }

  "applicationsByUserId" should {
    "convert to query" in {
      test(
        ApplicationQueries.applicationsByUserId(userIdOne, true),
        Map(
          ParamNames.UserId            -> Seq(s"$userIdOne"),
          ParamNames.WantSubscriptions -> Seq.empty
        )
      )
      test(
        ApplicationQueries.applicationsByUserId(userIdOne, false),
        Map(
          ParamNames.UserId            -> Seq(s"$userIdOne"),
          ParamNames.Status            -> Seq("EXCLUDING_DELETED"),
          ParamNames.WantSubscriptions -> Seq.empty
        )
      )
    }
  }

  "applicationsByUserIdAndEnvironment" should {
    "convert to query" in {
      test(
        ApplicationQueries.applicationsByUserIdAndEnvironment(userIdOne, Environment.SANDBOX),
        ParamNames.UserId      -> s"$userIdOne",
        ParamNames.Environment -> "SANDBOX",
        ParamNames.Status      -> "EXCLUDING_DELETED"
      )
    }
  }

  "applicationsByStateAndDate" should {
    "convert to query" in {
      test(ApplicationQueries.applicationsByStateAndDate(State.PRODUCTION, instant), ParamNames.Status -> "PRODUCTION", ParamNames.StatusDateBefore -> nowAsText)
    }
  }

  "applicationsByStates" should {
    "convert to query" in {
      test(
        GeneralOpenEndedApplicationQuery(List(
          MatchManyStatesQP(NonEmptyList.one(State.PRE_PRODUCTION) ++ List(State.PRODUCTION, State.PENDING_GATEKEEPER_APPROVAL))
        )),
        Map(
          ParamNames.Status -> Seq("PRE_PRODUCTION", "PRODUCTION", "PENDING_GATEKEEPER_CHECK")
        )
      )
    }
  }

  "applicationsByApiContext" should {
    "convert to query" in {
      test(ApplicationQueries.applicationsByApiContext(apiContextOne), ParamNames.ApiContext -> s"$apiContextOne")
    }
  }

  "applicationsByApiIdentifier" should {
    "convert to query" in {
      test(
        ApplicationQueries.applicationsByApiIdentifier(apiIdentifierOne),
        ParamNames.ApiContext    -> s"${apiIdentifierOne.context}",
        ParamNames.ApiVersionNbr -> s"${apiIdentifierOne.versionNbr}"
      )
    }
  }

  "QueryParamsToQueryBuilder" should {
    "convert GenericUserAgentQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(GenericUserAgentQP("bob"))))
    }
    "convert NoSubscriptionsQP to query" in {
      testOfNoValue(GeneralOpenEndedApplicationQuery(List(NoSubscriptionsQP)), ParamNames.NoSubscriptions)
    }
    "convert HasSubscriptionsQP to query" in {
      testOfNoValue(GeneralOpenEndedApplicationQuery(List(HasSubscriptionsQP)), ParamNames.HasSubscriptions)
    }
    "convert ApiContextQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(ApiContextQP(apiContextOne))), ParamNames.ApiContext -> s"$apiContextOne")
    }
    "convert ApiVersionNbrQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(ApiVersionNbrQP(apiVersionNbrOne))), ParamNames.ApiVersionNbr -> s"$apiVersionNbrOne")
    }
    "convert LastUsedAfterQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(LastUsedAfterQP(instant))), ParamNames.LastUsedAfter -> nowAsText)
    }
    "convert LastUsedBeforeQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(LastUsedBeforeQP(instant))), ParamNames.LastUsedBefore -> nowAsText)
    }
    "convert UserIdQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(UserIdQP(userIdOne))), ParamNames.UserId -> s"$userIdOne")
    }
    "convert EnvironmentQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(EnvironmentQP(Environment.SANDBOX))), ParamNames.Environment -> "SANDBOX")
    }
    "convert IncludeDeletedQP to query" in {
      testOfNoValue(GeneralOpenEndedApplicationQuery(List(IncludeDeletedQP)), ParamNames.IncludeDeleted)
    }
    "convert NoRestrictionQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(NoRestrictionQP)), ParamNames.DeleteRestriction -> "NO_RESTRICTION")
    }
    "convert DoNotDeleteQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(DoNotDeleteQP)), ParamNames.DeleteRestriction -> "DO_NOT_DELETE")
    }
    "convert ActiveStateQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(ActiveStateQP)), ParamNames.Status -> "ACTIVE")
    }
    "convert ExcludeDeletedQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(ExcludeDeletedQP)), ParamNames.Status -> "EXCLUDING_DELETED")
    }
    "convert BlockedStateQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(BlockedStateQP)), ParamNames.Status -> "BLOCKED")
    }
    "convert NoStateFilteringQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(NoStateFilteringQP)), ParamNames.Status -> "ANY")
    }
    "convert MatchAccessTypeQP(value) to query" in {
      test(GeneralOpenEndedApplicationQuery(List(MatchAccessTypeQP(AccessType.STANDARD))), ParamNames.AccessType -> "STANDARD")
    }
    "convert MatchOneStateQP(value) to query" in {
      test(GeneralOpenEndedApplicationQuery(List(MatchOneStateQP(State.PRODUCTION))), ParamNames.Status -> "PRODUCTION")
    }
    "convert MatchManyStatesQP(value) to query" in {
      test(
        GeneralOpenEndedApplicationQuery(List(MatchManyStatesQP(NonEmptyList.of(State.PRODUCTION, State.TESTING)))),
        Map(ParamNames.Status -> Seq("PRODUCTION", "CREATED"))
      )
    }
    "convert AppStateBeforeDateQP(value) to query" in {
      test(GeneralOpenEndedApplicationQuery(List(AppStateBeforeDateQP(instant))), ParamNames.StatusDateBefore -> nowAsText)
    }
    "convert SearchTextQP(value) to query" in {
      test(GeneralOpenEndedApplicationQuery(List(SearchTextQP("bob"))), ParamNames.Search -> "bob")
    }
    "convert NameQP(value) to query" in {
      test(GeneralOpenEndedApplicationQuery(List(NameQP("bob"))), ParamNames.Name -> "bob")
    }
    "convert VerificationCodeQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(VerificationCodeQP("bob"))), ParamNames.VerificationCode -> "bob")
    }
    "convert AnyAccessTypeQP to query" in {
      test(GeneralOpenEndedApplicationQuery(List(AnyAccessTypeQP)), ParamNames.AccessType -> "ANY")
    }
    "convert UserIdQP with pagination to query" in {
      test(PaginatedApplicationQuery(List(UserIdQP(userIdOne)), Sorting.NoSorting, Pagination()), ParamNames.UserId -> s"$userIdOne", ParamNames.PageNbr -> "1")
    }
  }

  "paramsForPagination" should {
    import Pagination.Defaults

    "convert to query" in {
      QueryParamsToQueryStringMap.paramsForPagination(Pagination(Defaults.PageSize, Defaults.PageNbr)) shouldBe Map(ParamNames.PageNbr -> Seq("1"))
      QueryParamsToQueryStringMap.paramsForPagination(Pagination(Defaults.PageSize, 5)) shouldBe Map(ParamNames.PageNbr -> Seq("5"))
      QueryParamsToQueryStringMap.paramsForPagination(Pagination(20, Defaults.PageNbr)) shouldBe Map(ParamNames.PageSize -> Seq("20"))
    }
  }
}
