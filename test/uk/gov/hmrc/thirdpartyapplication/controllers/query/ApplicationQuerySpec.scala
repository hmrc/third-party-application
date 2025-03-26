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

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaboratorsFixtures

class ApplicationQuerySpec extends HmrcSpec with ApplicationWithCollaboratorsFixtures with EitherValues {

  val appOneParam      = "applicationId"   -> Seq(applicationIdOne.toString)
  val pageSizeParam    = "pageSize"        -> Seq("10")
  val noSubsParam      = "noSubscriptions" -> Seq()
  val irrelevantHeader = Map("someheadername" -> Seq("bob"))

  "attemptToConstructQuery" should {
    val test = (ps: Map[String, Seq[String]], hs: Map[String, Seq[String]]) => ApplicationQuery.attemptToConstructQuery(ps, hs).toEither

    "work when given a correct applicationId" in {
      test(Map(appOneParam), Map.empty).value shouldBe ApplicationQuery.ById(applicationIdOne)
    }

    "work when given a correct applicationId and some irrelevant header" in {
      test(Map(appOneParam), irrelevantHeader).value shouldBe ApplicationQuery.ById(applicationIdOne)
    }

    "fail for applicationId with pageSize" in {
      test(Map(appOneParam, pageSizeParam), Map.empty) shouldBe Left(NonEmptyList.one("queries with identifiers cannot be matched with other parameters, sorting or pagination"))
    }

    "fail for applicationId with pageSize first" in {
      test(Map(pageSizeParam, appOneParam), Map.empty) shouldBe Left(NonEmptyList.one("queries with identifiers cannot be matched with other parameters, sorting or pagination"))
    }

  }
}
