/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.models.db

import uk.gov.hmrc.thirdpartyapplication.domain.models.{Environment, _}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.{HmrcSpec, UpliftRequestSamples}
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId

class ApplicationDataSpec extends HmrcSpec with UpliftRequestSamples {
  import ApiIdentifierSyntax._

  "ApplicationData" should {
    "for version 1 requests" should {
      "do not set the check information when app is created without subs" in {
        val token = Token(ClientId.random, "st")

        val request = CreateApplicationRequestV1(
          name = "bob",
          environment = Environment.PRODUCTION,
          collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
          subscriptions = None
        )

        ApplicationData.create(request, "bob", token).checkInformation shouldBe None
      }

      "set the check information for subscriptions when app is created with subs" in {
        val token = Token(ClientId.random, "st")

        val request = CreateApplicationRequestV1(
          name = "bob",
          environment = Environment.PRODUCTION,
          collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
          subscriptions = Some(Set("context".asIdentifier))
        )

        ApplicationData.create(request, "bob", token).checkInformation.value.apiSubscriptionsConfirmed shouldBe true
      }

      "ensure correct grant length when app is created" in {
        val token = Token(ClientId.random, "st")

        val request = CreateApplicationRequestV1(
          name = "bob",
          environment = Environment.PRODUCTION,
          collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
          subscriptions = None
        )

        val grantLengthInDays = 547
        ApplicationData.create(request, "bob", token).grantLength shouldBe grantLengthInDays
      }
    }

    "for version 2 requests" should {
      val token = Token(ClientId.random, "st")

      val request = CreateApplicationRequestV2(
        name = "bob",
        environment = Environment.PRODUCTION,
        collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
        upliftRequest = makeUpliftRequest(ApiIdentifier.random),
        requestedBy = "user@example.com",
        sandboxApplicationId = ApplicationId.random
      )

      "not set the check information at all" in {
        ApplicationData.create(request, "bob", token).checkInformation shouldBe None
      }

      "ensure correct grant length when app is created" in {
        ApplicationData.create(request, "bob", token).grantLength shouldBe 547
      }
    }
  }
}
