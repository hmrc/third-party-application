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
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders

class HeaderValidatorSpec extends HmrcSpec with EitherValues {
  val serverToken = HttpHeaders.SERVER_TOKEN_HEADER -> Seq("ABC")
  val userAgent   = HttpHeaders.INTERNAL_USER_AGENT -> Seq("XYZ")

  "parseParams" should {
    val test = HeaderValidator.parseHeaders _ andThen (_.toEither)

    "extract valid header - server token" in {
      test(Map(serverToken)).value shouldBe List(ServerTokenQP("ABC"))
    }

    "extract valid params - user agent" in {
      test(Map(userAgent)).value shouldBe List(UserAgentQP("XYZ"))
    }

    "extract valid params - both" in {
      test(Map(serverToken, userAgent)).value shouldBe List(ServerTokenQP("ABC"), UserAgentQP("XYZ"))
    }

    "extract valid params regardless of case - server token" in {
      test(Map(HttpHeaders.SERVER_TOKEN_HEADER.toUpperCase -> Seq("ABC"))).value shouldBe List(ServerTokenQP("ABC"))
    }

    "error on params with multiple values" in {
      inside(test(Map(HttpHeaders.SERVER_TOKEN_HEADER -> Seq("ABC", "DEF")))) {
        case Left(nel) => nel should (reportErrorForAllowsOnlyOneValue("X-server-token"))
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

  private def reportErrorForAllowsOnlyOneValue(word: String) = new ErrorIncludes(s"Multiple $word values are not permitted")

}
