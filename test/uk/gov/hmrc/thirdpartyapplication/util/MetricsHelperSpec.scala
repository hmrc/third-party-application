/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.util

import uk.gov.hmrc.play.test.UnitSpec

class MetricsHelperSpec extends UnitSpec with MetricsHelper {

  "sanitiseGrafanaNodeName" should {

    "replace dot correctly" in {
      sanitiseGrafanaNodeName("application.com") shouldBe "application-com"
    }

    "replace forward slash correctly" in {
      sanitiseGrafanaNodeName("organisations/trusts") shouldBe "organisations-trusts"
    }

    "replace multiple forward slashs correctly" in {
      sanitiseGrafanaNodeName("organisations/trusts/context") shouldBe "organisations-trusts-context"
    }

    "replace back slash correctly" in {
      sanitiseGrafanaNodeName("organisations\\trusts") shouldBe "organisations-trusts"
    }

    "replace round brackets correctly" in {
      sanitiseGrafanaNodeName("Get Vat Done (Fast)") shouldBe "Get Vat Done -Fast-"
    }

    "replace more than one pair of round brackets correctly" in {
      sanitiseGrafanaNodeName("(Get) Vat Done (Fast)") shouldBe "-Get- Vat Done -Fast-"
    }

    "replace stars correctly" in {
      sanitiseGrafanaNodeName("Don't be such a ****") shouldBe "Don't be such a ----"
    }

    "replace pipes correctly" in {
      sanitiseGrafanaNodeName("The | Awesome | Dreams") shouldBe "The - Awesome - Dreams"
    }

    "replace ampersands correctly" in {
      sanitiseGrafanaNodeName("Jack & the cow & who ate the Beanstalk") shouldBe "Jack - the cow - who ate the Beanstalk"
    }

    "replace question marks correctly" in {
      sanitiseGrafanaNodeName("Did the Grinch steal Xmas?") shouldBe "Did the Grinch steal Xmas-"
    }

    "replace exclamation marks correctly" in {
      sanitiseGrafanaNodeName("Jingle bells! Jingle bells! Jingle all the way!") shouldBe "Jingle bells- Jingle bells- Jingle all the way-"
    }

    "replace hash correctly" in {
      sanitiseGrafanaNodeName("I like # browns") shouldBe "I like - browns"
    }

    "replace @ correctly" in {
      sanitiseGrafanaNodeName("H@") shouldBe "H-"
    }
  }
}