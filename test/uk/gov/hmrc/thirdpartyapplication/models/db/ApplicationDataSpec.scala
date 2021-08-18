/*
 * Copyright 2021 HM Revenue & Customs
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

import uk.gov.hmrc.thirdpartyapplication.models.CreateApplicationRequest
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import org.scalatest.OptionValues

class ApplicationDataSpec extends HmrcSpec with OptionValues {
  import ApiIdentifierSyntax._
  
  "ApplicationData" should {
    "do not set the check information when app is created without subs" in {
      val token = Token(ClientId.random, "st")

      val request = CreateApplicationRequest(
        name = "bob",
        environment = Environment.PRODUCTION,
        collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
        subscriptions = List.empty
      )

      ApplicationData.create(request, "bob", token).checkInformation shouldBe None
    }

    "set the check information for subscriptions when app is created with subs" in {
      val token = Token(ClientId.random, "st")

      val request = CreateApplicationRequest(
        name = "bob",
        environment = Environment.PRODUCTION,
        collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
        subscriptions = List("context".asIdentifier)
      )

      ApplicationData.create(request, "bob", token).checkInformation.value.apiSubscriptionsConfirmed shouldBe true
    }
  }

}
