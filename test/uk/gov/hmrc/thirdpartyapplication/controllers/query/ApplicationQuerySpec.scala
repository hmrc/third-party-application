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

import org.scalatest.EitherValues

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaboratorsFixtures
import uk.gov.hmrc.thirdpartyapplication.controllers.query.ApplicationQuery._
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._

class ApplicationQuerySpec extends HmrcSpec with ApplicationWithCollaboratorsFixtures with EitherValues {

  "attemptToConstructQuery" should {
    val test = (ps: List[Param[_]]) => ApplicationQuery.attemptToConstructQuery(ps)

    "work when given a correct applicationId" in {
      test(List(ApplicationIdQP(applicationIdOne))) shouldBe ApplicationQuery.ById(applicationIdOne)
    }

    "work when given a correct clientId" in {
      test(List(ClientIdQP(clientIdOne))) shouldBe ApplicationQuery.ByClientId(clientIdOne, false)
    }

    "work when given a correct clientId and User Agent" in {
      test(List(ClientIdQP(clientIdOne), UserAgentQP(Param.ApiGatewayUserAgent))) shouldBe ApplicationQuery.ByClientId(clientIdOne, true)
      test(List(ClientIdQP(clientIdOne), UserAgentQP("Bob"))) shouldBe ApplicationQuery.ByClientId(clientIdOne, false)
    }

    "work when given a correct serverToken" in {
      test(List(ServerTokenQP("abc"))) shouldBe ApplicationQuery.ByServerToken("abc", false)
    }

    "work when given a correct serverToken and User Agent" in {
      test(List(ServerTokenQP("abc"), UserAgentQP(Param.ApiGatewayUserAgent))) shouldBe ApplicationQuery.ByServerToken("abc", true)
      test(List(ServerTokenQP("abc"), UserAgentQP("Bob"))) shouldBe ApplicationQuery.ByServerToken("abc", false)
    }

    "work when given a correct applicationId and some irrelevant header" in {
      test(List(ApplicationIdQP(applicationIdOne), UserAgentQP("XYZ"))) shouldBe ApplicationQuery.ById(applicationIdOne)
    }

    "work when given sorting and userId" in {
      test(List(UserIdQP(userIdOne), SortQP(Sorting.NameAscending))) shouldBe GeneralOpenEndedApplicationQuery(Sorting.NameAscending, List(UserIdQP(userIdOne)))
    }

    "work when given pagination, sorting and userId" in {
      test(List(UserIdQP(userIdOne), PageNbrQP(2), PageSizeQP(10), SortQP(Sorting.NameAscending))) shouldBe PaginatedApplicationQuery(
        Sorting.NameAscending,
        Pagination(10, 2),
        List(UserIdQP(userIdOne))
      )
    }

    "work when given page size, sorting and userId" in {
      test(List(UserIdQP(userIdOne), PageSizeQP(10), SortQP(Sorting.NameAscending))) shouldBe PaginatedApplicationQuery(
        Sorting.NameAscending,
        Pagination(10, 1),
        List(UserIdQP(userIdOne))
      )
    }

    "work when given page nbr, sorting and userId" in {
      test(List(UserIdQP(userIdOne), PageNbrQP(2), SortQP(Sorting.NameAscending))) shouldBe PaginatedApplicationQuery(
        Sorting.NameAscending,
        Pagination(50, 2),
        List(UserIdQP(userIdOne))
      )
    }
  }
}
