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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Environment
import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaboratorsFixtures
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

class ParamsValidatorSpec extends HmrcSpec with ApplicationWithCollaboratorsFixtures with EitherValues {

  val appOneParam      = "applicationId"   -> Seq(applicationIdOne.toString)
  val userIdParam      = "userId"          -> Seq(userIdOne.toString)
  val envParam         = "environment"     -> Seq(Environment.PRODUCTION.toString)
  val pageSizeParam    = "pageSize"        -> Seq("10")
  val noSubsParam      = "noSubscriptions" -> Seq()
  val irrelevantHeader = Map("someheadername" -> Seq("bob"))

  "parseAndValidateParams" should {
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
      test(Map(appOneParam, pageSizeParam), Map.empty) shouldBe Left(NonEmptyList.one("queries with identifiers cannot be matched with other parameters, sorting or pagination"))
    }
  }

  "validateParamCombinations" should {
    val test = (ps: List[Param[_]]) => ParamsValidator.checkUniqueParamsCombinations(ps).toEither

    "identify invalid combo of applicationId and clientId" in {
      test(List(Param.ApplicationIdQP(applicationIdOne), Param.ClientIdQP(clientIdOne))) shouldBe Left(NonEmptyList.one("clientId can only be used with an optional userAgent"))
    }
  }
}
