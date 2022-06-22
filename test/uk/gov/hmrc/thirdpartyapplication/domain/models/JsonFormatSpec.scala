/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{Json, Reads}

class JsonFormatSpec extends AnyWordSpec with Matchers {

  implicit val jsonFormat: Reads[CheckInformation] = CheckInformation.format

  "CheckInformation parsing from REST API Json" should {
    "parse fully populated json" in {
      val json =
        """
          |{
          |    "confirmedName" : true,
          |    "apiSubscriptionsConfirmed" : true,
          |    "apiSubscriptionConfigurationsConfirmed" : true,
          |    "providedPrivacyPolicyURL" : true,
          |    "providedTermsAndConditionsURL" : true,
          |    "teamConfirmed" : true
          |}
          |""".stripMargin

      val checkInformation: CheckInformation = Json.parse(json).as[CheckInformation]

      checkInformation.confirmedName shouldBe true
      checkInformation.apiSubscriptionsConfirmed shouldBe true
      checkInformation.apiSubscriptionConfigurationsConfirmed shouldBe true
      checkInformation.providedPrivacyPolicyURL shouldBe true
      checkInformation.providedTermsAndConditionsURL shouldBe true
      checkInformation.teamConfirmed shouldBe true
    }
  }

  val jsonWithoutDefaultingFields =
    """
      |{
      |    "confirmedName" : false,
      |    "providedPrivacyPolicyURL" : false,
      |    "providedTermsAndConditionsURL" : false
      |}
      |""".stripMargin

  "default teamConfirmed to false if missing from Json" in {
    val checkInformation: CheckInformation = Json.parse(jsonWithoutDefaultingFields).as[CheckInformation]

    checkInformation.teamConfirmed shouldBe false
  }

  "default subscriptionsConfirmed to false if missing from Json" in {
    val checkInformation: CheckInformation = Json.parse(jsonWithoutDefaultingFields).as[CheckInformation]

    checkInformation.apiSubscriptionsConfirmed shouldBe false
  }
  "default subscriptionConfigurationsConfirmed to false if missing from Json" in {
    val checkInformation: CheckInformation = Json.parse(jsonWithoutDefaultingFields).as[CheckInformation]

    checkInformation.apiSubscriptionConfigurationsConfirmed shouldBe false
  }
}
