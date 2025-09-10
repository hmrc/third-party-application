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
import cats.syntax.validated._
import org.scalatest.EitherValues
import org.scalatest.compatible.Assertion

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApiIdentifierFixtures, Environment}
import uk.gov.hmrc.apiplatform.modules.common.utils.{FixedClock, HmrcSpec}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, State}
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

class ParamsValidatorSpec
    extends HmrcSpec
    with ApplicationWithCollaboratorsFixtures
    with ApiIdentifierFixtures
    with EitherValues
    with FixedClock {

  val Pass = ().validNel

  def shouldFail[T](testThis: ErrorsOr[T]): Assertion = {
    inside(testThis) {
      case Invalid(_) => succeed
      case _          => fail("valid when expecting invalid")
    }
  }

  "checkLastUsedParamsCombinations" should {

    val test = (ps: List[NonUniqueFilterParam[_]]) => ParamsValidator.checkLastUsedParamsCombinations(ps)

    "pass when provided no last active dates" in {
      test(List.empty) shouldBe Pass
    }

    "pass when provided a last active before date" in {
      test(List(LastUsedAfterQP(instant))) shouldBe Pass
    }

    "pass when provided a last active after date" in {
      test(List(LastUsedAfterQP(instant))) shouldBe Pass
    }

    "pass when provided sensible last active before and last active after dates" in {
      test(List(LastUsedAfterQP(instant.minusSeconds(5)), LastUsedBeforeQP(instant))) shouldBe Pass
    }

    "fail when provided incorrect last active before and last active after dates" in {
      test(List(LastUsedAfterQP(instant), LastUsedBeforeQP(instant.minusSeconds(5)))) shouldBe "Cannot query for used after date that is after a given before date".invalidNel
    }
  }

  "checkSubscriptionsParamsCombinations" should {
    val test: List[NonUniqueFilterParam[_]] => ErrorsOr[Unit] = (ps) => ParamsValidator.checkSubscriptionsParamsCombinations(ps)
    val Context                                               = ApiContextQP(apiContextOne)
    val Version                                               = ApiVersionNbrQP(apiVersionNbrOne)

    "pass when given valid combinations" in {
      test(List(NoSubscriptionsQP)) shouldBe Pass
      test(List(HasSubscriptionsQP)) shouldBe Pass
      test(List(Context)) shouldBe Pass
      test(List(Context, Version)) shouldBe Pass
      test(List.empty) shouldBe Pass
    }

    "fail when given invalid combinations" in {
      shouldFail(test(List(NoSubscriptionsQP, HasSubscriptionsQP)))
      shouldFail(test(List(NoSubscriptionsQP, Context)))
      shouldFail(test(List(NoSubscriptionsQP, Context, Version)))
      shouldFail(test(List(NoSubscriptionsQP, Version)))
      shouldFail(test(List(HasSubscriptionsQP, Context)))
      shouldFail(test(List(HasSubscriptionsQP, Context, Version)))
      shouldFail(test(List(HasSubscriptionsQP, Version)))
      shouldFail(test(List(Version)))
    }
  }

  "checkUniqueParamsCombinations" should {
    val testBadCombo  = (us: NonEmptyList[UniqueFilterParam[_]], os: List[NonUniqueFilterParam[_]]) => ParamsValidator.checkUniqueParamsCombinations(us, os) should not be Pass
    val testGoodCombo = (us: NonEmptyList[UniqueFilterParam[_]], os: List[NonUniqueFilterParam[_]]) => ParamsValidator.checkUniqueParamsCombinations(us, os) shouldBe Pass

    "pass when given a correct applicationId" in {
      testGoodCombo(NonEmptyList.of(ApplicationIdQP(applicationIdOne)), List.empty)
    }

    "pass when given a correct clientId" in {
      testGoodCombo(NonEmptyList.of(ClientIdQP(clientIdOne)), List.empty)
    }

    "pass when given a correct clientId and User Agent" in {
      testGoodCombo(NonEmptyList.of(ClientIdQP(clientIdOne)), List(ApiGatewayUserAgentQP))
      testGoodCombo(NonEmptyList.of(ClientIdQP(clientIdOne)), List(GenericUserAgentQP("Bob")))
    }

    "pass when given a correct serverToken" in {
      testGoodCombo(NonEmptyList.of(ServerTokenQP("abc")), List.empty)
    }

    "pass when given a correct serverToken and User Agent" in {
      testGoodCombo(NonEmptyList.of(ServerTokenQP("abc")), List(ApiGatewayUserAgentQP))
      testGoodCombo(NonEmptyList.of(ServerTokenQP("abc")), List(GenericUserAgentQP("Bob")))
    }

    "pass when given a correct applicationId and some irrelevant header" in {
      testGoodCombo(NonEmptyList.of(ApplicationIdQP(applicationIdOne)), List(GenericUserAgentQP("XYZ")))
    }

    "fail when mixing two ids" in {
      testBadCombo(NonEmptyList.of(ApplicationIdQP(applicationIdOne), ClientIdQP(clientIdOne)), List.empty)
      testBadCombo(NonEmptyList.of(ApplicationIdQP(applicationIdOne), ServerTokenQP("ABC")), List.empty)
      testBadCombo(NonEmptyList.of(ClientIdQP(clientIdOne), ServerTokenQP("ABC")), List.empty)
    }
  }

  "checkVerificationCodeUsesDeleteExclusion" should {
    val testBadCombo  = (ps: List[NonUniqueFilterParam[_]]) => ParamsValidator.checkVerificationCodeUsesDeleteExclusion(ps) should not be Pass
    val testGoodCombo = (ps: List[NonUniqueFilterParam[_]]) => ParamsValidator.checkVerificationCodeUsesDeleteExclusion(ps) shouldBe Pass

    "pass when given a verification code and exclude deleted" in {
      testGoodCombo(List(ExcludeDeletedQP, VerificationCodeQP("ABC")))
    }
    "fail when given a verification code only" in {
      testBadCombo(List(VerificationCodeQP("ABC")))
    }
    "fail when given a verification code and a state filter" in {
      testBadCombo(List(ActiveStateQP, VerificationCodeQP("ABC")))
    }
  }

  "checkAppStateFilters" should {
    val oneState     = MatchOneStateQP(State.PRODUCTION)
    val manyState    = MatchManyStatesQP(NonEmptyList.of(State.PRODUCTION, State.PRE_PRODUCTION))
    val blockedState = BlockedStateQP
    val dateBefore   = Param.AppStateBeforeDateQP(instant)

    val testBadCombo  = (ps: List[NonUniqueFilterParam[_]]) => ParamsValidator.checkAppStateFilters(ps) should not be Pass
    val testGoodCombo = (ps: List[NonUniqueFilterParam[_]]) => ParamsValidator.checkAppStateFilters(ps) shouldBe Pass

    "pass when only a state filter" in {
      testGoodCombo(oneState :: Nil)
      testGoodCombo(manyState :: Nil)
      testGoodCombo(blockedState :: Nil)
    }

    "pass when one state with a date before" in {
      testGoodCombo(oneState :: dateBefore :: Nil)
    }

    "fail when no state but with a date before" in {
      testBadCombo(dateBefore :: Nil)
    }

    "fail when inappropriate state with a date before" in {
      testBadCombo(manyState :: dateBefore :: Nil)
      testBadCombo(blockedState :: dateBefore :: Nil)
    }
  }

  "parseAndValidateParams" should {
    val appOneParam      = "applicationId"   -> Seq(applicationIdOne.toString)
    val userIdParam      = "userId"          -> Seq(userIdOne.toString)
    val clientIdParam    = "clientId"        -> Seq(userIdOne.toString)
    val envParam         = "environment"     -> Seq(Environment.PRODUCTION.toString)
    val pageSizeParam    = "pageSize"        -> Seq("10")
    val noSubsParam      = "noSubscriptions" -> Seq()
    val irrelevantHeader = Map("someheadername" -> Seq("bob"))

    val test = (ps: Map[String, Seq[String]], hs: Map[String, Seq[String]]) => ParamsValidator.parseAndValidateParams(ps, hs).toEither

    "work when given a correct applicationId" in {
      test(Map(appOneParam), Map.empty).value shouldBe List(ApplicationIdQP(applicationIdOne))
    }

    "work when given a correct applicationId and some irrelevant header" in {
      test(Map(appOneParam), irrelevantHeader).value shouldBe List(ApplicationIdQP(applicationIdOne))
    }

    "work when given a correct environment and userId" in {
      test(Map(userIdParam, envParam), Map.empty).value shouldBe List(UserIdQP(userIdOne), EnvironmentQP(Environment.PRODUCTION))
    }

    "fail for applicationId with pageSize" in {
      test(Map(appOneParam, pageSizeParam), Map.empty) shouldBe Left(NonEmptyList.one("Cannot mix unique queries with sorting or pagination"))
    }

    "fail for applicationId, clientId, pageSize and two subscription params" in {
      test(Map(appOneParam, clientIdParam, pageSizeParam, noSubsParam, "context" -> Seq(apiContextOne.toString)), Map.empty) shouldBe
        Left(NonEmptyList.of(
          "Cannot mix one or more unique query params (serverToken, clientId and applicationId)",
          "Cannot mix unique queries with sorting or pagination",
          "Cannot query for no subscriptions and then query context"
        ))
    }
  }
}
