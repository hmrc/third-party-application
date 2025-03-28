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

import cats.data.NonEmptyList
import org.scalatest.EitherValues
import org.scalatest.matchers.{MatchResult, Matcher}

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.{FixedClock, HmrcSpec}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaboratorsFixtures
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

class QueryParamValidatorSpec extends HmrcSpec with ApplicationWithCollaboratorsFixtures with EitherValues with FixedClock {
  val appOneParam   = "applicationId"   -> Seq(applicationIdOne.toString)
  val pageSizeParam = "pageSize"        -> Seq("10")
  val noSubsParam   = "noSubscriptions" -> Seq()
  val invalidItem   = Map("someheadername" -> Seq("bob"))

  "parseParams" should {
    val test = QueryParamValidator.parseParams _ andThen (_.toEither)

    "extract valid params - applicationId" in {
      test(Map(appOneParam)).value shouldBe List(ApplicationIdQP(applicationIdOne))
    }

    "extract valid params - clientId" in {
      test(Map("clientId" -> Seq(clientIdOne.toString))).value shouldBe List(ClientIdQP(clientIdOne))
    }

    "extract valid params - apiContext" in {
      test(Map("context" -> Seq("context1"))).value shouldBe List(ApiContextQP(ApiContext("context1")))
    }

    "extract valid params - apiVersionNbr" in {
      test(Map("versionNbr" -> Seq("1.0"))).value shouldBe List(ApiVersionNbrQP(ApiVersionNbr("1.0")))
    }

    "extract valid params - hasSubscriptions" in {
      test(Map("oneOrMoreSubscriptions" -> Seq())).value shouldBe List(HasSubscriptionsQP)
    }

    "extract valid params - noSubscriptions" in {
      test(Map(noSubsParam)).value shouldBe List(NoSubscriptionsQP)
    }

    "extract valid params - pageSize" in {
      test(Map("pageSize" -> Seq("10"))).value shouldBe List(PageSizeQP(10))
    }

    "extract valid params - pageNbr" in {
      test(Map("pageNbr" -> Seq("3"))).value shouldBe List(PageNbrQP(3))
    }

    "extract valid params - status filter" in {
      test(Map("status" -> Seq("CREATED"))).value shouldBe List(StatusFilterQP(AppStatusFilter.Created))
      test(Map("status" -> Seq("PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION"))).value shouldBe List(StatusFilterQP(AppStatusFilter.PendingResponsibleIndividualVerification))
      test(Map("status" -> Seq("PENDING_GATEKEEPER_CHECK"))).value shouldBe List(StatusFilterQP(AppStatusFilter.PendingGatekeeperCheck))
      test(Map("status" -> Seq("PENDING_SUBMITTER_VERIFICATION"))).value shouldBe List(StatusFilterQP(AppStatusFilter.PendingSubmitterVerification))
      test(Map("status" -> Seq("ACTIVE"))).value shouldBe List(StatusFilterQP(AppStatusFilter.Active))
      test(Map("status" -> Seq("DELETED"))).value shouldBe List(StatusFilterQP(AppStatusFilter.WasDeleted))
      test(Map("status" -> Seq("EXCLUDING_DELETED"))).value shouldBe List(StatusFilterQP(AppStatusFilter.ExcludingDeleted))
      test(Map("status" -> Seq("BLOCKED"))).value shouldBe List(StatusFilterQP(AppStatusFilter.Blocked))
      test(Map("status" -> Seq("ANY"))).value shouldBe List(StatusFilterQP(AppStatusFilter.NoFiltering))
    }

    "extract valid params - sort filter" in {
      test(Map("sort" -> Seq("NO_SORT"))).value shouldBe List(SortQP(Sorting.NoSorting))
    }

    "extract valid params - accessType filter" in {
      test(Map("accessType" -> Seq("STANDARD"))).value shouldBe List(AccessTypeQP(Some(AccessType.STANDARD)))
      test(Map("accessType" -> Seq("ROPC"))).value shouldBe List(AccessTypeQP(Some(AccessType.ROPC)))
      test(Map("accessType" -> Seq("PRIVILEGED"))).value shouldBe List(AccessTypeQP(Some(AccessType.PRIVILEGED)))
      test(Map("accessType" -> Seq("ANY"))).value shouldBe List(AccessTypeQP(None))
    }

    "extract valid params - search filter" in {
      test(Map("search" -> Seq("ANY"))).value shouldBe List(SearchTextQP("ANY"))
    }

    "extract valid params - include deleted filter" in {
      test(Map("includeDeleted" -> Seq("true"))).value shouldBe List(IncludeDeletedQP(true))
      test(Map("includeDeleted" -> Seq("false"))).value shouldBe List(IncludeDeletedQP(false))
    }

    "extract valid params - delete restriction filter" in {
      test(Map("deleteRestriction" -> Seq("DO_NOT_DELETE"))).value shouldBe List(DeleteRestrictionQP(DeleteRestrictionFilter.DoNotDelete))
      test(Map("deleteRestriction" -> Seq("NO_RESTRICTION"))).value shouldBe List(DeleteRestrictionQP(DeleteRestrictionFilter.NoRestriction))
    }

    "extract valid params - last used before filter" in {
      test(Map("lastUsedBefore" -> Seq(nowAsText))).value shouldBe List(LastUsedBeforeQP(instant))
    }

    "extract valid params - last used after filter" in {
      test(Map("lastUsedAfter" -> Seq(nowAsText))).value shouldBe List(LastUsedAfterQP(instant))
    }

    // -----

    "extract valid params - applicationId, pageSize" in {
      test(Map(appOneParam, pageSizeParam)).value shouldBe List(ApplicationIdQP(applicationIdOne), PageSizeQP(10))
    }

    // -----

    "extract valid params regardless of case - applicationId" in {
      test(Map("APPLICATIONID" -> Seq(applicationIdOne.toString()))).value shouldBe List(ApplicationIdQP(applicationIdOne))
    }

    // -----

    "error on params with multiple values" in {
      inside(test(Map("applicationId" -> Seq(applicationIdOne.toString(), applicationIdTwo.toString())))) {
        case Left(nel) => nel should (reportErrorForAllowsOnlyOneValue("applicationId"))
      }
    }

    "multiple errors" in {
      inside(test(Map("applicationId" -> Seq(applicationIdOne.toString(), applicationIdTwo.toString()), "bob" -> Seq("fred")))) {
        case Left(nel) => nel should (reportErrorForAllowsOnlyOneValue("applicationId") and reportErrorForInvalidParameterName("bob"))
      }
    }
  }

  private class ErrorIncludes(tagLine: String) extends Matcher[NonEmptyList[ErrorMessage]] {

    def apply(in: NonEmptyList[ErrorMessage]) = {
      MatchResult(
        in.find(msg => msg.contains(tagLine)).isDefined,
        s"Error did not contain $tagLine",
        s"Error did contain $tagLine"
      )
    }
  }

  private def reportErrorForAllowsOnlyOneValue(word: String)   = new ErrorIncludes(s"Multiple $word query parameters are not permitted")
  private def reportErrorForInvalidParameterName(word: String) = new ErrorIncludes(s"$word is not a valid query parameter")

}
