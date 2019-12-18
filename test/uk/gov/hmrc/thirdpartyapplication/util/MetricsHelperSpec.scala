/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE_2.0
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
      sanitiseGrafanaNodeName("application.com") shouldBe "application_com"
    }

    "replace forward slash correctly" in {
      sanitiseGrafanaNodeName("organisations/trusts") shouldBe "organisations_trusts"
    }

    "replace multiple forward slashs correctly" in {
      sanitiseGrafanaNodeName("organisations/trusts/context") shouldBe "organisations_trusts_context"
    }

    "replace back slash correctly" in {
      sanitiseGrafanaNodeName("organisations\\trusts") shouldBe "organisations_trusts"
    }

    "replace round brackets correctly" in {
      sanitiseGrafanaNodeName("Get Vat Done (Fast)") shouldBe "Get Vat Done _Fast_"
    }

    "replace more than one pair of round brackets correctly" in {
      sanitiseGrafanaNodeName("(Get) Vat Done (Fast)") shouldBe "_Get_ Vat Done _Fast_"
    }

    "replace stars correctly" in {
      sanitiseGrafanaNodeName("Don't be such a ****") shouldBe "Don't be such a ____"
    }

    "replace pipes correctly" in {
      sanitiseGrafanaNodeName("The | Awesome | Dreams") shouldBe "The _ Awesome _ Dreams"
    }

    "replace ampersands correctly" in {
      sanitiseGrafanaNodeName("Jack & the cow & who ate the Beanstalk") shouldBe "Jack _ the cow _ who ate the Beanstalk"
    }

    "replace question marks correctly" in {
      sanitiseGrafanaNodeName("Did the Grinch steal Xmas?") shouldBe "Did the Grinch steal Xmas_"
    }

    "replace exclamation marks correctly" in {
      sanitiseGrafanaNodeName("Jingle bells! Jingle bells! Jingle all the way!") shouldBe "Jingle bells_ Jingle bells_ Jingle all the way_"
    }

    "replace hash correctly" in {
      sanitiseGrafanaNodeName("I like # browns") shouldBe "I like _ browns"
    }

    "replace @ correctly" in {
      sanitiseGrafanaNodeName("H@") shouldBe "H_"
    }
  }
}