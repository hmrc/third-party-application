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

package uk.gov.hmrc.thirdpartyapplication.models

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.thirdpartyapplication.domain.models.CheckInformation
import play.api.libs.json.Json

class MongoFormatSpec extends AnyWordSpec with Matchers {

  "CheckInformation parsing from the database" should {
    "parse fully populated json" in {
      val json =
        """
          |{
          |    "confirmedName" : true,
          |    "apiSubscriptionsConfirmed" : true,
          |    "providedPrivacyPolicyURL" : true,
          |    "providedTermsAndConditionsURL" : true,
          |    "teamConfirmed" : true
          |}
          |""".stripMargin

      val checkInformation: CheckInformation = Json.parse(json).as[CheckInformation]

      checkInformation.confirmedName shouldBe true
      checkInformation.apiSubscriptionsConfirmed shouldBe true
      checkInformation.providedPrivacyPolicyURL shouldBe true
      checkInformation.providedTermsAndConditionsURL shouldBe true
      checkInformation.teamConfirmed shouldBe true
    }
  }

  "default teamConfirmed to false if missing from Json" in {
    val json =
      """
        |{
        |    "confirmedName" : false,
        |    "apiSubscriptionsConfirmed" : false,
        |    "providedPrivacyPolicyURL" : false,
        |    "providedTermsAndConditionsURL" : false
        |}
        |""".stripMargin

    val checkInformation: CheckInformation = Json.parse(json).as[CheckInformation]

    checkInformation.teamConfirmed shouldBe false
  }
}
