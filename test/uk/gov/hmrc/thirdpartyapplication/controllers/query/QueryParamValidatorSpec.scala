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

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaboratorsFixtures
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

class QueryParamValidatorSpec extends HmrcSpec with ApplicationWithCollaboratorsFixtures with EitherValues {
  val appOneParam   = "applicationId"   -> Seq(applicationIdOne.toString)
  val pageSizeParam = "pageSize"        -> Seq("10")
  val noSubsParam   = "noSubscriptions" -> Seq()
  val invalidItem   = Map("someheadername" -> Seq("bob"))

  "parseParams" should {
    val test = QueryParamValidator.parseParams _ andThen (_.toEither)

    "extract valid params - applicationId" in {
      test(Map(appOneParam)).value shouldBe List(ApplicationIdQP(applicationIdOne))
    }

    "extract valid params - noSubscriptions" in {
      test(Map(noSubsParam)).value shouldBe List(NoSubscriptionsQP)
    }

    "extract valid params - applicationId, pageSize" in {
      test(Map(appOneParam, pageSizeParam)).value shouldBe List(ApplicationIdQP(applicationIdOne), PageSizeQP(10))
    }

    "extract valid params regardless of case - applicationId" in {
      test(Map("APPLICATIONID" -> Seq(applicationIdOne.toString()))).value shouldBe List(ApplicationIdQP(applicationIdOne))
    }

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
