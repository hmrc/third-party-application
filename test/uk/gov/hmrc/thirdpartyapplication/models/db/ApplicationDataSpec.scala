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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.GrantLength
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.{
  CreateApplicationRequest,
  CreateApplicationRequestV1,
  CreateApplicationRequestV2,
  StandardAccessDataToCopy
}
import uk.gov.hmrc.thirdpartyapplication.util.{CollaboratorTestData, UpliftRequestSamples}

class ApplicationDataSpec extends HmrcSpec with UpliftRequestSamples with CollaboratorTestData {
  import ApiIdentifierSyntax._

  "StoredApplication" should {
    val refreshTokensAvailableFor = GrantLength.EIGHTEEN_MONTHS.period
    "for version 1 requests" should {
      "do not set the check information when app is created without subs" in {
        val token = StoredToken(ClientId.random, "st")

        val request: CreateApplicationRequest =
          CreateApplicationRequestV1.create(
            name = "bob",
            access = Access.Standard(),
            description = None,
            environment = Environment.PRODUCTION,
            collaborators = Set("jim@example.com".admin()),
            subscriptions = None
          )

        StoredApplication.create(request, "bob", token).checkInformation shouldBe None
      }

      "set the check information for subscriptions when app is created with subs" in {
        val token = StoredToken(ClientId.random, "st")

        val request: CreateApplicationRequest =
          CreateApplicationRequestV1.create(
            name = "bob",
            access = Access.Standard(),
            description = None,
            environment = Environment.PRODUCTION,
            collaborators = Set("jim@example.com".admin()),
            subscriptions = Some(Set("context".asIdentifier))
          )

        StoredApplication.create(request, "bob", token).checkInformation.value.apiSubscriptionsConfirmed shouldBe true
      }

      "ensure correct grant length when app is created" in {
        val token = StoredToken(ClientId.random, "st")

        val request: CreateApplicationRequest =
          CreateApplicationRequestV1.create(
            name = "bob",
            access = Access.Standard(),
            description = None,
            environment = Environment.PRODUCTION,
            collaborators = Set("jim@example.com".admin()),
            subscriptions = Some(Set("context".asIdentifier))
          )

        StoredApplication.create(request, "bob", token).refreshTokensAvailableFor shouldBe refreshTokensAvailableFor
      }
    }

    "for version 2 requests" should {
      val token = StoredToken(ClientId.random, "st")

      val request: CreateApplicationRequest =
        CreateApplicationRequestV2.create(
          name = "bob",
          access = StandardAccessDataToCopy(),
          description = None,
          environment = Environment.PRODUCTION,
          collaborators = Set("jim@example.com".admin()),
          upliftRequest = makeUpliftRequest(ApiIdentifier.random),
          requestedBy = "user@example.com",
          sandboxApplicationId = ApplicationId.random
        )

      "not set the check information at all" in {
        StoredApplication.create(request, "bob", token).checkInformation shouldBe None
      }

      "ensure correct grant length when app is created" in {
        StoredApplication.create(request, "bob", token).refreshTokensAvailableFor shouldBe refreshTokensAvailableFor
      }
    }
  }
}
