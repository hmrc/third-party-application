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

package uk.gov.hmrc.thirdpartyapplication.util

class MetricsHelperSpec extends HmrcSpec with MetricsHelper {

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
      sanitiseGrafanaNodeName("Get Vat Done (Fast)") shouldBe "Get_Vat_Done__Fast_"
    }

    "replace more than one pair of round brackets correctly" in {
      sanitiseGrafanaNodeName("(Get) Vat Done (Fast)") shouldBe "_Get__Vat_Done__Fast_"
    }

    "replace stars correctly" in {
      sanitiseGrafanaNodeName("Don't be such a ****") shouldBe "Don_t_be_such_a_____"
    }

    "replace pipes correctly" in {
      sanitiseGrafanaNodeName("This|Is|Awesome") shouldBe "This_Is_Awesome"
    }

    "replace ampersands correctly" in {
      sanitiseGrafanaNodeName("Jack&the cow&who ate the Beanstalk") shouldBe "Jack_the_cow_who_ate_the_Beanstalk"
    }

    "replace question marks correctly" in {
      sanitiseGrafanaNodeName("Did the Grinch steal Xmas?") shouldBe "Did_the_Grinch_steal_Xmas_"
    }

    "replace exclamation marks correctly" in {
      sanitiseGrafanaNodeName("Jingle bells! Jingle bells! Jingle all the way!") shouldBe "Jingle_bells__Jingle_bells__Jingle_all_the_way_"
    }

    "replace hash correctly" in {
      sanitiseGrafanaNodeName("I like # browns") shouldBe "I_like___browns"
    }

    "replace @ correctly" in {
      sanitiseGrafanaNodeName("H@") shouldBe "H_"
    }

    "replace ' correctly" in {
      sanitiseGrafanaNodeName("Trust me it's working") shouldBe "Trust_me_it_s_working"
    }

    "replace - correctly" in {
      sanitiseGrafanaNodeName("This is a hyphen -") shouldBe "This_is_a_hyphen__"
    }
  }
}
