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
import cats.data.Validated.Invalid
import org.scalatest.EitherValues
import org.scalatest.compatible.Assertion
import org.scalatest.matchers.{MatchResult, Matcher}

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.{FixedClock, HmrcSpec}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, State}
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

class QueryParamValidatorSpec extends HmrcSpec with ApplicationWithCollaboratorsFixtures with EitherValues with FixedClock {
  import cats.implicits._

  val appOneParam   = "applicationId"   -> Seq(applicationIdOne.toString)
  val pageSizeParam = "pageSize"        -> Seq("10")
  val noSubsParam   = "noSubscriptions" -> Seq()
  val invalidItem   = Map("someheadername" -> Seq("bob"))

  "parseParams" when {
    val test = QueryParamValidator.parseParams _

    def shouldFail[T](testThis: ErrorsOr[T]): Assertion = {
      inside(testThis) {
        case Invalid(_) => succeed
        case _          => fail("valid when expecting invalid")
      }
    }

    "expecteds" should {
      "no-value-expected passes" in {
        QueryParamValidator.NoValueExpected("param")(Seq()) shouldBe ().validNel
      }
      "no-value-expected fails" in {
        shouldFail(QueryParamValidator.NoValueExpected("param")(Seq("1")))
      }

      "single-value-expected passes" in {
        QueryParamValidator.SingleValueExpected("param")(Seq("1")) shouldBe "1".validNel
      }
      "single-value-expected fails" in {
        shouldFail(QueryParamValidator.SingleValueExpected("param")(Seq("1", "2")))
        shouldFail(QueryParamValidator.SingleValueExpected("param")(Seq()))
      }

      "boolean-expected passes" in {
        QueryParamValidator.BooleanValueExpected("param")("true") shouldBe true.validNel
        QueryParamValidator.BooleanValueExpected("param")("false") shouldBe false.validNel
      }

      "boolean-expected fails" in {
        shouldFail(QueryParamValidator.BooleanValueExpected("param")("not-a-boolean"))
      }

      "integer-expected passes" in {
        QueryParamValidator.IntValueExpected("param")("1") shouldBe 1.validNel
        QueryParamValidator.IntValueExpected("param")("4") shouldBe 4.validNel
        QueryParamValidator.IntValueExpected("param")("-5") shouldBe -5.validNel
      }

      "integer-expected fails" in {
        shouldFail(QueryParamValidator.IntValueExpected("param")("not-an-int"))
        shouldFail(QueryParamValidator.IntValueExpected("param")("1.5"))
      }

      "positive-integer-expected passes" in {
        QueryParamValidator.IntValueExpected("param")("1") shouldBe 1.validNel
        QueryParamValidator.IntValueExpected("param")("4") shouldBe 4.validNel
        QueryParamValidator.IntValueExpected("param")("0") shouldBe 0.validNel
      }

      "positive-integer-expected fails" in {
        shouldFail(QueryParamValidator.PositiveIntValueExpected("param")("-5"))
        shouldFail(QueryParamValidator.PositiveIntValueExpected("param")("not-an-int"))
        shouldFail(QueryParamValidator.PositiveIntValueExpected("param")("1.5"))
      }

      "instant-value-expected passes" in {
        QueryParamValidator.InstantValueExpected("param")(nowAsText) shouldBe instant.validNel
      }

      "instant-value-expected fails" in {
        shouldFail(QueryParamValidator.InstantValueExpected("param")("not-an-instant"))
      }
    }

    "extract" should {
      "extract valid params - applicationId" in {
        test(Map(appOneParam)) shouldBe List(ApplicationIdQP(applicationIdOne)).validNel
      }

      "extract valid params - clientId" in {
        test(Map("clientId" -> Seq(clientIdOne.toString))) shouldBe List(ClientIdQP(clientIdOne)).validNel
      }

      "extract valid params - apiContext" in {
        test(Map("context" -> Seq("context1"))) shouldBe List(ApiContextQP(ApiContext("context1"))).validNel
      }

      "extract valid params - apiVersionNbr" in {
        test(Map("versionNbr" -> Seq("1.0"))) shouldBe List(ApiVersionNbrQP(ApiVersionNbr("1.0"))).validNel
      }

      "extract valid params - hasSubscriptions" in {
        test(Map("oneOrMoreSubscriptions" -> Seq())) shouldBe List(HasSubscriptionsQP).validNel
      }

      "extract valid params - noSubscriptions" in {
        test(Map(noSubsParam)) shouldBe List(NoSubscriptionsQP).validNel
      }

      "extract valid params - pageSize" in {
        test(Map("pageSize" -> Seq("10"))) shouldBe List(PageSizeQP(10)).validNel
      }

      "extract valid params - pageNbr" in {
        test(Map("pageNbr" -> Seq("3"))) shouldBe List(PageNbrQP(3)).validNel
      }

      "extract valid params - status filter" in {
        test(Map("status" -> Seq("CREATED"))) shouldBe List(AppStateFilterQP(AppStateFilter.Matching(State.TESTING))).validNel
        test(Map("status" -> Seq("PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION"))) shouldBe List(
          AppStateFilterQP(AppStateFilter.Matching(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION))
        ).validNel
        test(Map("status" -> Seq("PENDING_GATEKEEPER_CHECK"))) shouldBe List(AppStateFilterQP(AppStateFilter.Matching(State.PENDING_GATEKEEPER_APPROVAL))).validNel
        test(Map("status" -> Seq("PENDING_SUBMITTER_VERIFICATION"))) shouldBe List(AppStateFilterQP(AppStateFilter.Matching(State.PENDING_REQUESTER_VERIFICATION))).validNel
        test(Map("status" -> Seq("ACTIVE"))) shouldBe List(AppStateFilterQP(AppStateFilter.Active)).validNel
        test(Map("status" -> Seq("DELETED"))) shouldBe List(AppStateFilterQP(AppStateFilter.Matching(State.DELETED))).validNel
        test(Map("status" -> Seq("EXCLUDING_DELETED"))) shouldBe List(AppStateFilterQP(AppStateFilter.ExcludingDeleted)).validNel
        test(Map("status" -> Seq("BLOCKED"))) shouldBe List(AppStateFilterQP(AppStateFilter.Blocked)).validNel
        test(Map("status" -> Seq("ANY"))) shouldBe List(AppStateFilterQP(AppStateFilter.NoFiltering)).validNel
      }

      "extract valid params - sort filter" in {
        test(Map("sort" -> Seq("LAST_USE_ASC"))) shouldBe List(SortQP(Sorting.LastUseDateAscending)).validNel
        test(Map("sort" -> Seq("LAST_USE_DESC"))) shouldBe List(SortQP(Sorting.LastUseDateDescending)).validNel
        test(Map("sort" -> Seq("NAME_ASC"))) shouldBe List(SortQP(Sorting.NameAscending)).validNel
        test(Map("sort" -> Seq("NAME_DESC"))) shouldBe List(SortQP(Sorting.NameDescending)).validNel
        test(Map("sort" -> Seq("SUBMITTED_ASC"))) shouldBe List(SortQP(Sorting.SubmittedAscending)).validNel
        test(Map("sort" -> Seq("SUBMITTED_DESC"))) shouldBe List(SortQP(Sorting.SubmittedDescending)).validNel
        test(Map("sort" -> Seq("NO_SORT"))) shouldBe List(SortQP(Sorting.NoSorting)).validNel
      }

      "extract valid params - accessType filter" in {
        test(Map("accessType" -> Seq("STANDARD"))) shouldBe List(AccessTypeQP(Some(AccessType.STANDARD))).validNel
        test(Map("accessType" -> Seq("ROPC"))) shouldBe List(AccessTypeQP(Some(AccessType.ROPC))).validNel
        test(Map("accessType" -> Seq("PRIVILEGED"))) shouldBe List(AccessTypeQP(Some(AccessType.PRIVILEGED))).validNel
        test(Map("accessType" -> Seq("ANY"))) shouldBe List(AccessTypeQP(None)).validNel
        shouldFail(test(Map("accessType" -> Seq("BOBBINS"))))
      }

      "extract valid params - search filter" in {
        test(Map("search" -> Seq("ANY"))) shouldBe List(SearchTextQP("ANY")).validNel
      }

      "extract valid params - include deleted filter" in {
        test(Map("includeDeleted" -> Seq("true"))) shouldBe List(IncludeDeletedQP(true)).validNel
        test(Map("includeDeleted" -> Seq("false"))) shouldBe List(IncludeDeletedQP(false)).validNel
      }

      "extract valid params - delete restriction filter" in {
        test(Map("deleteRestriction" -> Seq("DO_NOT_DELETE"))) shouldBe List(DoNotDeleteQP).validNel
        test(Map("deleteRestriction" -> Seq("NO_RESTRICTION"))) shouldBe List(NoRestrictionQP).validNel
        shouldFail(test(Map("deleteRestriction" -> Seq("Blah Blah Blah"))))
      }

      "extract valid params - last used before filter" in {
        test(Map("lastUsedBefore" -> Seq(nowAsText))) shouldBe List(LastUsedBeforeQP(instant)).validNel
      }

      "extract valid params - last used after filter" in {
        test(Map("lastUsedAfter" -> Seq(nowAsText))) shouldBe List(LastUsedAfterQP(instant)).validNel
      }

      // -----

      "extract valid params - applicationId, pageSize" in {
        test(Map(appOneParam, pageSizeParam)) shouldBe List(ApplicationIdQP(applicationIdOne), PageSizeQP(10)).validNel
      }

      // -----

      "extract valid params regardless of case - applicationId" in {
        test(Map("APPLICATIONID" -> Seq(applicationIdOne.toString()))) shouldBe List(ApplicationIdQP(applicationIdOne)).validNel
      }

      // -----

      "error on params with multiple applicationId values" in {
        inside(test(Map("applicationId" -> Seq(applicationIdOne.toString(), applicationIdTwo.toString()))).toEither) {
          case Left(nel) => nel should (reportErrorForAllowsOnlyOneValue("applicationId"))
        }
      }

      "multiple errors" in {
        inside(test(Map("applicationId" -> Seq(applicationIdOne.toString(), applicationIdTwo.toString()), "bob" -> Seq("fred"))).toEither) {
          case Left(nel) => nel should (reportErrorForAllowsOnlyOneValue("applicationId") and reportErrorForInvalidParameterName("bob"))
        }
      }

      "error on param with invalid applicationId value" in {
        inside(test(Map("applicationId" -> Seq("123"))).toEither) {
          case Left(nel) => nel should (new ErrorIncludes("123 is not a valid application id"))
        }
      }

      // -----

      "extract valid params regardless of case - userId" in {
        test(Map("USERID" -> Seq(userIdOne.toString()))) shouldBe List(UserIdQP(userIdOne)).validNel
        test(Map("userId" -> Seq(userIdOne.toString()))) shouldBe List(UserIdQP(userIdOne)).validNel
      }

      "error on params with multiple userId values" in {
        inside(test(Map("userId" -> Seq(userIdOne.toString(), userIdTwo.toString()))).toEither) {
          case Left(nel) => nel should (reportErrorForAllowsOnlyOneValue("userId"))
        }
      }

      "error on param with invalid userId value" in {
        inside(test(Map("userId" -> Seq("123"))).toEither) {
          case Left(nel) => nel should (new ErrorIncludes("123 is not a valid user id"))
        }
      }
    }

    // -----

    "extract valid params - environment" in {
      test(Map("environment" -> Seq("PRODUCTION"))) shouldBe List(EnvironmentQP(Environment.PRODUCTION)).validNel
      test(Map("environment" -> Seq("SANDBOX"))) shouldBe List(EnvironmentQP(Environment.SANDBOX)).validNel
    }

    "error on params with invalid environment" in {
      inside(test(Map("environment" -> Seq("BANG"))).toEither) {
        case Left(nel) => nel should (new ErrorIncludes("BANG is not a valid environment"))
      }
    }

    "multiple errors stack" in {
      test(Map("environment" -> Seq("BLAH BLAH"), "userId" -> Seq("ABC"))) shouldBe Invalid(NonEmptyList.of("BLAH BLAH is not a valid environment", "ABC is not a valid user id"))
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
